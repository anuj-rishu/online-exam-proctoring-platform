import base64
import threading
from pathlib import Path

import cv2
import numpy as np

MODELS_DIR = Path(__file__).parent / "models"
YUNET_PATH = MODELS_DIR / "face_detection_yunet.onnx"
SFACE_PATH = MODELS_DIR / "face_recognition_sface.onnx"


SFACE_MATCH_THRESHOLD = 0.363

_detector = cv2.FaceDetectorYN.create(
    str(YUNET_PATH), "", (320, 320),
    score_threshold=0.7, nms_threshold=0.3, top_k=5000,
)
_recognizer = cv2.FaceRecognizerSF.create(str(SFACE_PATH), "")

# Both models are shared singletons holding mutable state — detect() depends
# on an input size set moments earlier by setInputSize(). FastAPI runs these
# sync endpoints in a threadpool, so without this lock two concurrent
# requests interleave and one gets another's input size, which surfaces as
# an HTTP 500. Measured: at 8 concurrent requests, 42 of 48 failed.
#
# Serialising is the right trade rather than a model per thread: one request
# costs ~16 ms, so a single lock still clears ~60 req/s, while a second copy
# of the models would cost memory this host does not have to spare.
_model_lock = threading.Lock()


class NoFaceError(Exception):
    pass


def decode_data_url(data_url: str) -> np.ndarray:
    if "," in data_url:
        data_url = data_url.split(",", 1)[1]
    raw = base64.b64decode(data_url)
    arr = np.frombuffer(raw, dtype=np.uint8)
    image = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("Could not decode image")
    return image


def detect_faces(image: np.ndarray) -> np.ndarray:
    """Returns an (N, 15) array: x, y, w, h, then 5 landmark points, then score."""
    h, w = image.shape[:2]
    with _model_lock:
        _detector.setInputSize((w, h))
        _, faces = _detector.detect(image)
    if faces is None:
        return np.empty((0, 15))
    return faces


def largest_face(faces: np.ndarray) -> np.ndarray | None:
    if faces.shape[0] == 0:
        return None
    areas = faces[:, 2] * faces[:, 3]
    return faces[int(np.argmax(areas))]


def image_sharpness(image: np.ndarray) -> float:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def image_brightness(image: np.ndarray) -> float:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    return float(np.mean(gray))


def largest_document_contour(image: np.ndarray) -> tuple[bool, float]:
    """Heuristic check for a card/document-shaped region: a sizeable, roughly
    rectangular contour. Deliberately lenient — a handheld card on a webcam
    is rarely a clean axis-aligned rectangle (tilt, lamination glare,
    rounded corners, cluttered background), so this only screens out frames
    with no card-like shape at all rather than demanding a precise match."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 40, 140)
    edges = cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=2)

    contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return False, 0.0

    frame_area = image.shape[0] * image.shape[1]
    best_fraction = 0.0
    found = False

    for contour in contours:
        area = cv2.contourArea(contour)
        if area < frame_area * 0.05:
            continue
        perimeter = cv2.arcLength(contour, True)
        approx = cv2.approxPolyDP(contour, 0.03 * perimeter, True)
        if len(approx) < 4 or len(approx) > 10:
            continue

        rect = cv2.minAreaRect(contour)
        (_, _), (rw, rh), _ = rect
        if rw == 0 or rh == 0:
            continue
        aspect = max(rw, rh) / min(rw, rh)
        # Typical ID/student card ratios run roughly 1.2:1 to 1.9:1; widened
        # generously here to tolerate camera tilt and imprecise framing.
        if 1.0 <= aspect <= 2.8:
            fraction = area / frame_area
            if fraction > best_fraction:
                best_fraction = fraction
                found = True

    return found, best_fraction


def align_and_embed(image: np.ndarray, face_row: np.ndarray) -> np.ndarray:
    with _model_lock:
        aligned = _recognizer.alignCrop(image, face_row)
        return _recognizer.feature(aligned)


def compare_embeddings(a: np.ndarray, b: np.ndarray) -> float:
    with _model_lock:
        return float(_recognizer.match(a, b, cv2.FaceRecognizerSF_FR_COSINE))


def embedding_to_list(embedding: np.ndarray) -> list[float]:
    """SFace's feature() returns a (1, N) float32 matrix — flatten it to a
    plain list so it can cross the Java/Python boundary as JSON."""
    return embedding.flatten().tolist()


def embedding_from_list(values: list[float]) -> np.ndarray:
    return np.array(values, dtype=np.float32).reshape(1, -1)


# --- Head pose -------------------------------------------------------------
#
# YuNet returns 5 landmarks per face: right eye, left eye, nose tip, right
# mouth corner, left mouth corner (columns 4..13, as x,y pairs). That is
# enough for a coarse but reliable "is this person facing their screen?"
# estimate without loading a second model:
#
#   yaw   - where the nose sits horizontally between the two eyes. Facing
#           forward puts it near the midpoint; turning the head slides it
#           toward the eye on the side being turned away from.
#   pitch - where the nose sits vertically between the eye line and the
#           mouth line. Looking down pushes it toward the mouth.
#
# Both are returned as roughly -1..1 offsets rather than degrees, because the
# ratios are what stay stable across different faces and camera distances.

LANDMARK_RIGHT_EYE = 0
LANDMARK_LEFT_EYE = 1
LANDMARK_NOSE = 2
LANDMARK_MOUTH_RIGHT = 3
LANDMARK_MOUTH_LEFT = 4

#: Beyond these the student is looking away from their screen rather than
#: merely glancing. Deliberately loose — reading the lower half of a monitor
#: or glancing at a keyboard must not trip it.
YAW_LIMIT = 0.42
PITCH_LIMIT = 0.55


def landmarks(face_row: np.ndarray) -> np.ndarray:
    """The 5 (x, y) landmark points of a detected face, as a (5, 2) array."""
    return face_row[4:14].reshape(5, 2)


def head_pose(face_row: np.ndarray) -> tuple[float, float]:
    """Returns (yaw, pitch) as normalised offsets. (0, 0) is facing straight
    at the camera; positive yaw means turned toward their own left."""
    points = landmarks(face_row)

    right_eye = points[LANDMARK_RIGHT_EYE]
    left_eye = points[LANDMARK_LEFT_EYE]
    nose = points[LANDMARK_NOSE]
    mouth_mid = (points[LANDMARK_MOUTH_RIGHT] + points[LANDMARK_MOUTH_LEFT]) / 2.0

    eye_mid = (right_eye + left_eye) / 2.0
    eye_span = float(np.linalg.norm(left_eye - right_eye))
    if eye_span < 1e-6:
        return 0.0, 0.0

    yaw = float((nose[0] - eye_mid[0]) / eye_span)

    vertical_span = float(abs(mouth_mid[1] - eye_mid[1]))
    if vertical_span < 1e-6:
        return yaw, 0.0

    # 0.5 would place the nose exactly halfway between eyes and mouth, which
    # is roughly where it sits when facing forward; re-centre on that.
    pitch = float(((nose[1] - eye_mid[1]) / vertical_span) - 0.5) * 2.0

    return yaw, pitch


def is_looking_away(face_row: np.ndarray) -> tuple[bool, float, float]:
    yaw, pitch = head_pose(face_row)
    return (abs(yaw) > YAW_LIMIT or abs(pitch) > PITCH_LIMIT), yaw, pitch


#: How far the head must turn to count as a deliberate turn for the liveness
#: challenge. Higher than YAW_LIMIT: drifting slightly off-centre shouldn't
#: satisfy a challenge, it has to be an unmistakable turn.
TURN_THRESHOLD = 0.30

#: And how close to centre counts as "facing forward again".
CENTER_THRESHOLD = 0.15


def pose_direction(yaw: float) -> str:
    """Classifies a yaw offset into the directions the liveness challenge
    asks for. Mirrored to the student's own point of view: turning toward
    their left moves the nose toward their left eye, which is negative yaw
    in image coordinates."""
    if yaw <= -TURN_THRESHOLD:
        return "LEFT"
    if yaw >= TURN_THRESHOLD:
        return "RIGHT"
    if abs(yaw) <= CENTER_THRESHOLD:
        return "CENTER"
    return "PARTIAL"
