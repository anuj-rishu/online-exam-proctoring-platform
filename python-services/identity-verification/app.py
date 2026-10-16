import logging

from fastapi import FastAPI
from pydantic import BaseModel

import face_utils

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("identity-verification")

app = FastAPI(title="Insider Lab — Identity Verification Service")


class SingleImageRequest(BaseModel):
    image: str


class MatchRequest(BaseModel):
    facePhoto: str
    idCardPhoto: str


class LiveFaceCheckRequest(BaseModel):
    image: str
    referenceEmbedding: list[float]


class PoseCheckRequest(BaseModel):
    image: str


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/position-check")
def position_check(req: SingleImageRequest):
    """Live feedback while a student frames their face for capture —
    the server-side replacement for the old in-browser MediaPipe loop."""
    try:
        image = face_utils.decode_data_url(req.image)
    except ValueError:
        return {"faceDetected": False, "singleFace": False, "centered": False,
                "properDistance": False, "ready": False, "message": "Could not read image"}

    faces = face_utils.detect_faces(image)
    h, w = image.shape[:2]

    if faces.shape[0] == 0:
        return {"faceDetected": False, "singleFace": False, "centered": False,
                "properDistance": False, "ready": False, "message": "No face detected"}

    single_face = bool(faces.shape[0] == 1)
    face = face_utils.largest_face(faces)
    fx, fy, fw, fh = (float(face[0]), float(face[1]), float(face[2]), float(face[3]))

    center_x = (fx + fw / 2) / w
    center_y = (fy + fh / 2) / h
    face_height_ratio = fh / h

    centered = bool(0.25 < center_x < 0.75 and 0.15 < center_y < 0.65)
    proper_distance = bool(0.18 < face_height_ratio < 0.65)

    ready = bool(single_face and centered and proper_distance)

    if not single_face:
        message = "Multiple faces detected — only one person should be visible"
    elif face_height_ratio <= 0.18:
        message = "Move closer to the camera"
    elif face_height_ratio >= 0.65:
        message = "Move back from the camera"
    elif not centered:
        message = "Center your face in the frame"
    else:
        message = "Face and position look good — you can capture"

    return {
        "faceDetected": True,
        "singleFace": single_face,
        "centered": centered,
        "properDistance": proper_distance,
        "ready": ready,
        "message": message,
    }


@app.post("/id-card-check")
def id_card_check(req: SingleImageRequest):
    try:
        image = face_utils.decode_data_url(req.image)
    except ValueError:
        return {"cardDetected": False, "faceOnCard": False, "ready": False,
                "message": "Could not read image"}

    card_detected, _fraction = face_utils.largest_document_contour(image)
    faces = face_utils.detect_faces(image)
    face_on_card = faces.shape[0] > 0

    sharpness = face_utils.image_sharpness(image)
    brightness = face_utils.image_brightness(image)
    sharp_enough = sharpness > 15.0
    bright_enough = 30.0 < brightness < 245.0

    # Gate on image quality only; many valid ID cards carry no photograph.
    ready = sharp_enough and bright_enough

    if not sharp_enough:
        message = "Image is blurry — hold steady and try again"
    elif not bright_enough:
        message = "Lighting is too dark or too bright — adjust and try again"
    else:
        message = "ID card looks good — you can capture"

    return {
        "cardDetected": card_detected,
        "faceOnCard": face_on_card,
        "sharpness": round(sharpness, 1),
        "brightness": round(brightness, 1),
        "ready": ready,
        "message": message,
    }


@app.post("/verify-identity")
def verify_identity(req: MatchRequest):
    """One-shot check at final submit: does the live selfie match the
    photo on the uploaded ID card?"""
    try:
        selfie = face_utils.decode_data_url(req.facePhoto)
        id_card = face_utils.decode_data_url(req.idCardPhoto)
    except ValueError:
        return {"isMatch": False, "confidence": 0.0, "faceDetectedOnSelfie": False,
                "faceDetectedOnId": False, "message": "Could not read one of the images"}

    selfie_faces = face_utils.detect_faces(selfie)
    id_faces = face_utils.detect_faces(id_card)

    selfie_face = face_utils.largest_face(selfie_faces)
    id_face = face_utils.largest_face(id_faces)

    if selfie_face is None:
        return {"isMatch": False, "confidence": 0.0, "faceDetectedOnSelfie": False,
                "faceDetectedOnId": id_face is not None, "message": "No face detected in the webcam photo"}

    selfie_embedding = face_utils.align_and_embed(selfie, selfie_face)

    # Still return the selfie embedding; without it in-exam face checks are skipped.
    if id_face is None:
        return {"isMatch": False, "confidence": 0.0, "faceDetectedOnSelfie": True,
                "faceDetectedOnId": False, "message": "No face detected on the ID card",
                "selfieEmbedding": face_utils.embedding_to_list(selfie_embedding)}

    id_embedding = face_utils.align_and_embed(id_card, id_face)
    similarity = face_utils.compare_embeddings(selfie_embedding, id_embedding)

    is_match = similarity >= face_utils.SFACE_MATCH_THRESHOLD

    log.info("Identity match check: similarity=%.4f threshold=%.4f match=%s",
              similarity, face_utils.SFACE_MATCH_THRESHOLD, is_match)

    return {
        "isMatch": is_match,
        "confidence": round(similarity, 4),
        "faceDetectedOnSelfie": True,
        "faceDetectedOnId": True,
        "message": "Face matches the ID card" if is_match else "Face does not match the ID card — please retake",
        # Enrolled once here and stored by the caller so later in-exam checks
        # don't need to re-process the ID card photo every time.
        "selfieEmbedding": face_utils.embedding_to_list(selfie_embedding),
    }


@app.post("/verify-live")
def verify_live(req: LiveFaceCheckRequest):
    """Periodic re-check during the exam itself: is exactly one person in
    frame, and is it still the person who enrolled? Distinct from
    /verify-identity (a one-shot gate at entry) — this runs repeatedly
    against the embedding captured at enrollment, so a webcam frame never
    needs to be compared against the ID card photo again."""
    try:
        image = face_utils.decode_data_url(req.image)
    except ValueError:
        return {"faceCount": 0, "isMatch": True, "confidence": 0.0,
                "message": "Could not read image"}

    faces = face_utils.detect_faces(image)
    face_count = int(faces.shape[0])

    if face_count == 0:
        # Not this endpoint's concern — the client already has a separate,
        # faster local check for "no face visible" that pauses the exam.
        return {"faceCount": 0, "isMatch": True, "confidence": 0.0,
                "message": "No face detected"}

    if face_count > 1:
        return {"faceCount": face_count, "isMatch": False, "confidence": 0.0,
                "message": "Multiple faces detected in frame"}

    face = face_utils.largest_face(faces)
    looking_away, yaw, pitch = face_utils.is_looking_away(face)

    if not req.referenceEmbedding:
        return {"faceCount": 1, "isMatch": True, "confidence": 0.0,
                "lookingAway": looking_away, "yaw": round(yaw, 3), "pitch": round(pitch, 3),
                "message": "No enrolled face on record to compare against"}

    live_embedding = face_utils.align_and_embed(image, face)
    reference_embedding = face_utils.embedding_from_list(req.referenceEmbedding)
    similarity = face_utils.compare_embeddings(live_embedding, reference_embedding)
    is_match = similarity >= face_utils.SFACE_MATCH_THRESHOLD

    if not is_match:
        log.warning("Live face check mismatch: similarity=%.4f threshold=%.4f",
                     similarity, face_utils.SFACE_MATCH_THRESHOLD)

    if looking_away:
        log.info("Live face check: looking away (yaw=%.2f pitch=%.2f)", yaw, pitch)

    return {
        "faceCount": 1,
        "isMatch": is_match,
        "confidence": round(similarity, 4),
        "lookingAway": looking_away,
        "yaw": round(yaw, 3),
        "pitch": round(pitch, 3),
        "message": "Face matches enrolled identity" if is_match
                    else "Different face detected — does not match the enrolled identity",
    }


@app.post("/pose-check")
def pose_check(req: PoseCheckRequest):
    """Which way is the head turned? Used to drive the liveness challenge at
    enrollment: a printed photo or a still image on a phone cannot turn to
    face a direction named at random a moment earlier."""
    try:
        image = face_utils.decode_data_url(req.image)
    except ValueError:
        return {"faceDetected": False, "direction": "NONE", "yaw": 0.0, "pitch": 0.0,
                "message": "Could not read image"}

    faces = face_utils.detect_faces(image)
    if faces.shape[0] == 0:
        return {"faceDetected": False, "direction": "NONE", "yaw": 0.0, "pitch": 0.0,
                "message": "No face detected"}

    if faces.shape[0] > 1:
        return {"faceDetected": True, "direction": "MULTIPLE", "yaw": 0.0, "pitch": 0.0,
                "message": "Multiple faces detected — only one person should be visible"}

    face = face_utils.largest_face(faces)
    yaw, pitch = face_utils.head_pose(face)
    direction = face_utils.pose_direction(yaw)

    return {
        "faceDetected": True,
        "direction": direction,
        "yaw": round(yaw, 3),
        "pitch": round(pitch, 3),
        "message": f"Head is facing {direction.lower()}",
    }
