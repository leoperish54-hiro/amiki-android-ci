package com.amiki.app;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and verifies the optional Amiki offline English model.
 *
 * The archive is pinned to an immutable upstream revision and SHA-256 digest.
 * Extraction is size-bounded and rejects path traversal. The downloaded model
 * is kept inside app-private storage and is not bundled into the APK.
 */
final class AmikiOfflineSpeechModel {
    private static final String MODEL_DIR = "vosk-model-small-en-us-0.15";
    private static final String MODEL_URL =
            "https://huggingface.co/rhasspy/vosk-models/resolve/"
            + "e7ac2109d134b5f2404ba95389b2fb51916d4cab/"
            + "en/vosk-model-small-en-us-0.15.zip?download=true";
    private static final String EXPECTED_SHA256 =
            "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";
    private static final long MAX_DOWNLOAD_BYTES = 60L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 250L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 10000;

    interface ProgressListener {
        void onProgress(int percent);
    }

    private AmikiOfflineSpeechModel() { }

    static File modelDirectory(Context context) {
        return new File(context.getFilesDir(), MODEL_DIR);
    }

    static boolean isInstalled(Context context) {
        return context != null && isModelRoot(modelDirectory(context));
    }

    static void download(Context context, ProgressListener listener) throws IOException {
        if (context == null) throw new IllegalArgumentException("A context is required.");
        File installed = modelDirectory(context);
        if (isModelRoot(installed)) {
            report(listener, 100);
            return;
        }

        File archive = new File(context.getCacheDir(), "amiki-vosk-model.zip.part");
        File staging = new File(context.getFilesDir(), "amiki-vosk-model-staging");
        HttpURLConnection connection = null;
        try {
            if (archive.exists() && !archive.delete()) {
                throw new IOException("Could not clear a previous partial model archive.");
            }
            deleteRecursively(staging);
            if (!staging.mkdirs() && !staging.isDirectory()) {
                throw new IOException("Could not create private model staging storage.");
            }

            connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Amiki-Android/1.0");
            connection.setRequestProperty("Accept", "application/zip");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Model host returned HTTP " + status + ".");
            }
            long expectedLength = connection.getContentLengthLong();
            if (expectedLength > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Model archive exceeds the permitted download size.");
            }

            MessageDigest digest = sha256();
            long downloaded = 0L;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(archive))) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    downloaded += count;
                    if (downloaded > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Model download exceeded the permitted size.");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    if (expectedLength > 0L) {
                        report(listener, (int) Math.min(78L, downloaded * 78L / expectedLength));
                    }
                }
            }
            if (downloaded < 1024L * 1024L) {
                throw new IOException("The model archive is unexpectedly small.");
            }
            String actualDigest = hex(digest.digest());
            if (!EXPECTED_SHA256.equals(actualDigest)) {
                throw new IOException("Model archive integrity verification failed.");
            }
            report(listener, 80);

            extractSafely(archive, staging, listener);
            File modelRoot = findModelRoot(staging);
            if (modelRoot == null) {
                throw new IOException("The verified archive does not contain a valid Vosk model.");
            }
            deleteRecursively(installed);
            if (!modelRoot.renameTo(installed)) {
                throw new IOException("Could not atomically install the verified model.");
            }
            if (!isModelRoot(installed)) {
                deleteRecursively(installed);
                throw new IOException("Installed model validation failed.");
            }
            report(listener, 100);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable on this Android device.", impossible);
        } finally {
            if (connection != null) connection.disconnect();
            deleteRecursively(staging);
            if (archive.exists()) archive.delete();
        }
    }

    private static void extractSafely(File archive, File destination, ProgressListener listener)
            throws IOException {
        String rootPath = destination.getCanonicalPath() + File.separator;
        long extracted = 0L;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new IOException("Model archive contains too many entries.");
                }
                File output = new File(destination, entry.getName());
                String canonical = output.getCanonicalPath();
                if (!canonical.startsWith(rootPath)) {
                    throw new IOException("Model archive contains an unsafe path.");
                }
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        throw new IOException("Could not create a model directory.");
                    }
                } else {
                    File parent = output.getParentFile();
                    if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) {
                        throw new IOException("Could not create model extraction storage.");
                    }
                    try (BufferedOutputStream stream = new BufferedOutputStream(
                            new FileOutputStream(output))) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            extracted += count;
                            if (extracted > MAX_EXTRACTED_BYTES) {
                                throw new IOException("Extracted model exceeds the permitted size.");
                            }
                            stream.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        report(listener, 98);
    }

    private static File findModelRoot(File directory) {
        if (isModelRoot(directory)) return directory;
        File[] children = directory.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory() && isModelRoot(child)) return child;
        }
        return null;
    }

    private static boolean isModelRoot(File directory) {
        if (directory == null || !directory.isDirectory()) return false;
        return new File(directory, "am/final.mdl").isFile()
                && new File(directory, "conf/model.conf").isFile();
    }

    private static MessageDigest sha256() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static void report(ProgressListener listener, int progress) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
