package in.srminsider.labs.admin;

import org.springframework.web.multipart.MultipartFile;

public final class CsvUploadValidator {

    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private CsvUploadValidator() {
    }

    public static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please upload a CSV file");
        }

        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("The file is larger than the 5 MB limit");
        }

        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException("Only .csv files are accepted");
        }
    }
}
