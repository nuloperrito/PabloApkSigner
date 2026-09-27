package com.pablo.apksigner;

import com.pablo.apksigner.signing.*;
import com.pablo.apksigner.ui.Arg;
import com.pablo.apksigner.ui.ApkSignerGui;
import com.pablo.apksigner.ui.FileArgParser;
import com.pablo.apksigner.util.FileUtil;
import com.android.apksigner.ApkSignerTool;
import com.pablo.apksigner.signing.ZipAlign;
import com.pablo.apksigner.util.InvalidZipException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Main logic utility class, adapted for GUI calls
 */
public final class SignTool {

    // Modified entry point: directly launch GUI
    public static void main(String[] args) {
        try {
            ApkSignerGui window = new ApkSignerGui();
            window.open();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Changed execute to public for GUI thread invocation
    public static Result execute(Arg args) {
        SigningConfigGen signingConfigGen = null;

        int successCount = 0;
        int errorCount = 0;

        try {
            File outFolder = null;
            // FileArgParser can still be used because it relies on the Arg structure
            List<File> targetApkFiles = new FileArgParser().parseAndSortUniqueFilesNonRecursive(args.apkFile, "apk");

            if (targetApkFiles.isEmpty()) {
                logErr("ERROR: No APK files found in the specified path.");
                return new Result(true, 0, 0);
            }

            log("Source:");
            for (String path : FileArgParser.getDirSummary(targetApkFiles)) {
                log("\t" + path);
            }

            if (args.out != null && !args.out.trim().isEmpty()) {
                outFolder = new File(args.out);
                if (!outFolder.exists()) {
                    outFolder.mkdirs();
                }
                if (!outFolder.exists() || !outFolder.isDirectory()) {
                    logErr("ERROR: Output directory is invalid or cannot be created: " + args.out);
                    return new Result(true, 0, 0);
                }
            }

            if (!args.onlyVerify) {
                log("Keystore Settings:");
                // The parameters passed by the GUI should already handle SignArgs; generate configuration directly here
                signingConfigGen = new SigningConfigGen(args.signArgsList, args.ksIsDebug);
                for (SigningConfig signingConfig : signingConfigGen.signingConfig) {
                    try {
                        log("\t" + signingConfig.description());
                    } catch (Exception e) {
                        logErr("\tCould not read Keystore description: " + e.getMessage());
                    }
                }
            }

            if (args.lineageFilePath != null && !args.lineageFilePath.isEmpty()) {
                processLineagePath(args);
            }

            long startTime = System.currentTimeMillis();
            int iterCount = 0;
            List<File> tempFilesToDelete = new ArrayList<>();

            for (File targetApkFile : targetApkFiles) {
                iterCount++;
                File rootTargetFile = targetApkFile;

                log("\n" + String.format("%02d", iterCount) + ". " + targetApkFile.getName());

                if (args.dryRun) {
                    log("\t- (Dry run mode, skipping)");
                    continue;
                }

                if (!args.onlyVerify) {
                    AndroidApkSignerVerify.Result preCheck = verifySign(targetApkFile, rootTargetFile, args.checkCertSha256, false, true);

                    if (preCheck != null && args.allowResign) {
                        log("\tWARNING: Already signed - will resign. Old certificate info: " + preCheck.getCertCountString() + preCheck.getSchemaVersionInfoString());
                    } else if (preCheck != null) {
                        logErr("\t- Already signed, skipping (use --allowResign to force resign)");
                        errorCount++;
                        continue;
                    }
                }

                if (!args.onlyVerify) {
                    log("\n\tSigning (SIGN)");
                    log("\tFile: " + rootTargetFile.getCanonicalPath() + " (" + FileUtil.getFileSizeMb(targetApkFile) + ")");

                    File apkToSign = targetApkFile;

                    // ZipAlign step
                    apkToSign = zipAlign(apkToSign, rootTargetFile, outFolder, args);

                    if (apkToSign == null) {
                        logErr("ZipAlign failed, stopping processing for this file.");
                        errorCount++;
                        continue;
                    }

                    if (!args.overwrite && !args.skipZipAlign) {
                        tempFilesToDelete.add(apkToSign);
                    }

                    // Signing step
                    try {
                        apkToSign = sign(apkToSign, outFolder, signingConfigGen.signingConfig, args);
                    } catch (Exception e) {
                        StringBuilder msg = new StringBuilder("\t- Signing failed: " + e.getMessage());
                        for(StackTraceElement ste : e.getStackTrace()) {
                            msg.append("\n\t\t at ").append(ste.toString());
                        }
                        logErr(msg.toString());
                        errorCount++;
                        continue;
                    }

                    // Update targetApkFile to point to the final artifact for verification
                    targetApkFile = apkToSign;
                }

                log("\n\tVerification (VERIFY)");
                log("\tFile: " + targetApkFile.getCanonicalPath());

                boolean zipAlignVerified = args.skipZipAlign || verifyZipAlign(targetApkFile, rootTargetFile, args);
                boolean sigVerified = verifySign(targetApkFile, rootTargetFile, args.checkCertSha256, args.verbose, false) != null;

                if (zipAlignVerified && sigVerified) {
                    successCount++;
                } else {
                    errorCount++;
                }
            }

            if (iterCount == 0) {
                log("No APK files found.");
            }

            deleteTempFiles(args, tempFilesToDelete);

            log(String.format(Locale.US, "\n[%s]\nProcessing complete. Success: %d, Errors: %d, Time elapsed: %.2f seconds.",
                    new Date().toString(), successCount, errorCount, (double) (System.currentTimeMillis() - startTime) / 1000.0));

        } catch (Exception e) {
            logException(args, e);
            return new Result(true, successCount, errorCount);
        } finally {
            cleanup(signingConfigGen);
        }
        return new Result(false, successCount, errorCount);
    }

    // Used to construct apk tool parameters
    public static String[] createApkToolArgs(Arg arguments, List<SigningConfig> list, File targetApkFile, File outFile) {
        List<String> args = new ArrayList<>();
        args.add("sign");

        if (arguments.verbose) args.add("--verbose");
        if (arguments.lineageFilePath != null && !arguments.lineageFilePath.isEmpty()) {
            args.add("--lineage");
            args.add(arguments.lineageFilePath);
        }

        args.add("--v1-signing-enabled"); args.add(Boolean.toString(arguments.v1SchemeEnabled));
        args.add("--v2-signing-enabled"); args.add(Boolean.toString(arguments.v2SchemeEnabled));
        args.add("--v3-signing-enabled"); args.add(Boolean.toString(arguments.v3SchemeEnabled));
        args.add("--v4-signing-enabled"); args.add(Boolean.toString(arguments.v4SchemeEnabled));

        for (int i = 0; i < list.size(); i++) {
            args.add("--ks"); args.add(list.get(i).keystore.getAbsolutePath());
            args.add("--ks-key-alias"); args.add(list.get(i).ksAlias);
            args.add("--ks-pass"); args.add("pass:" + list.get(i).ksPass);
            args.add("--key-pass"); args.add("pass:" + list.get(i).ksKeyPass);
            args.add("--out"); args.add(outFile.getAbsolutePath());

            if (i + 1 < list.size()) {
                args.add("--next-signer");
            }
        }
        args.add(targetApkFile.getAbsolutePath());
        return args.toArray(new String[0]);
    }

    private static void processLineagePath(Arg args) throws IOException {
        File lineageFile = new File(args.lineageFilePath);
        if (!lineageFile.exists() || !lineageFile.isFile()) {
            throw new IllegalArgumentException("Lineage file does not exist: " + args.lineageFilePath);
        }
        log("Lineage File: " + lineageFile.getName());
    }

    private static void deleteTempFiles(Arg args, List<File> tempFilesToDelete) {
        for (File file : tempFilesToDelete) {
            if (args.verbose) log("Deleting temporary file: " + file);
            file.delete();
        }
    }

    private static void cleanup(SigningConfigGen signingConfigGen) {
        if (signingConfigGen != null) signingConfigGen.cleanUp();
    }

    private static void logException(Arg args, Exception e) {
        logErr(e.getMessage());
        if (args.debug) e.printStackTrace();
    }

    private static File zipAlign(File targetApkFile, File rootTargetFile, File outFolder, Arg arguments) {
        if (!arguments.skipZipAlign) {
            String fileName = FileUtil.getFileNameWithoutExtension(targetApkFile);
            fileName = fileName.replace("-unaligned", "") + "-aligned";
            File outFile = new File(outFolder != null ? outFolder : targetApkFile.getParentFile(), fileName + "." + FileUtil.getFileExtension(targetApkFile));

            if (outFile.exists()) outFile.delete();

            String logMsg = "\t- ";
            boolean success = false;

            try (RandomAccessFile raf = new RandomAccessFile(targetApkFile, "r");
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                ZipAlign.alignZip(raf, fos);
                success = true;
                logMsg += "ZipAlign successful";
            } catch (IOException | InvalidZipException e) {
                logMsg += "Could not Align: " + e.getMessage();
                if (arguments.debug) e.printStackTrace();
            }

            logConditionally(logMsg, outFile, !rootTargetFile.equals(outFile), false);

            if (success) {
                if (arguments.overwrite) {
                    targetApkFile.delete();
                    if (!outFile.renameTo(targetApkFile)) {
                        logErr("Could not rename Aligned file to overwrite original.");
                        return null;
                    }
                    outFile = targetApkFile;
                }
                return outFile;
            } else {
                if (outFile.exists()) outFile.delete();
                return null;
            }
        }
        return targetApkFile;
    }

    private static boolean verifyZipAlign(File targetApkFile, File rootTargetFile, Arg arguments) {
        if (!arguments.skipZipAlign) {
            String logMsg = "\t- ";
            boolean success;
            try (RandomAccessFile raf = new RandomAccessFile(targetApkFile, "r")) {
                success = ZipAlign.verifyZip(raf);
                logMsg += success ? "ZipAlign verification passed" : "ZipAlign verification failed";
            } catch (IOException | InvalidZipException e) {
                logMsg += "ZipAlign verification exception: " + e.getMessage();
                success = false;
            }
            logConditionally(logMsg, targetApkFile, !targetApkFile.equals(rootTargetFile), !success);
            return success;
        }
        return true;
    }

    private static File sign(File targetApkFile, File outFolder, List<SigningConfig> signingConfigs, Arg arguments) throws Exception {
        File outFile = targetApkFile;
        String errMsg = "";
        if (!arguments.overwrite) {
            String fileName = FileUtil.getFileNameWithoutExtension(targetApkFile);
            fileName = fileName.replace("-unsigned", "");
            fileName += (signingConfigs.size() == 1 && signingConfigs.get(0).isDebugType) ? "-debugSigned" : "-signed";
            outFile = new File(outFolder != null ? outFolder : targetApkFile.getParentFile(), fileName + "." + FileUtil.getFileExtension(targetApkFile));
            if (outFile.exists()) outFile.delete();
        }

        ByteArrayOutputStream apkSignerOutStream = new ByteArrayOutputStream();
        ByteArrayOutputStream apkSignerErrStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        // Capture both stdout and stderr from ApkSignerTool
        System.setOut(new PrintStream(apkSignerOutStream, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(apkSignerErrStream, true, StandardCharsets.UTF_8));

        Exception caughtException = null;
        try {
            ApkSignerTool.subMain(createApkToolArgs(arguments, signingConfigs, targetApkFile, outFile));
        } catch (Exception e) {
            caughtException = e;
            errMsg = (e.getMessage() != null && !e.getMessage().trim().isEmpty())
                    ? e.getMessage().trim()
                    : e.getClass().getSimpleName();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String output = apkSignerOutStream.toString(StandardCharsets.UTF_8).trim();
        String errOutput = apkSignerErrStream.toString(StandardCharsets.UTF_8).trim();

        // Check if output file was actually generated
        if (errMsg.isEmpty()) {
            if (!outFile.exists() || outFile.length() == 0) {
                errMsg = !errOutput.isEmpty()
                        ? errOutput
                        : "Output file was not generated or is empty: " + outFile.getAbsolutePath();
            } else if (!errOutput.isEmpty() && arguments.verbose) {
                // Log non-fatal warnings if present
                logErr("\t\t" + errOutput);
            }
        }

        if (errMsg.isEmpty()) {
            log("\t- Signing successful");
            if (arguments.verbose && !output.isEmpty()) {
                log("\t\t" + output);
            }
            return outFile;
        } else {
            // Re-throw to let execute() properly catch the failure, increment errorCount, and abort verification
            if (caughtException != null) {
                throw caughtException;
            } else {
                throw new IOException(errMsg);
            }
        }
    }

    private static AndroidApkSignerVerify.Result verifySign(File targetApkFile, File rootTargetFile, String[] checkHashes, boolean verbose, boolean preCheckVerify) {
        try {
            AndroidApkSignerVerify verifier = new AndroidApkSignerVerify();
            AndroidApkSignerVerify.Result result = verifier.verify(targetApkFile, null, null, null, false);

            if (!preCheckVerify) {
                String logMsg = result.verified ? "\t- Signature verification passed " + result.getCertCountString() + result.getSchemaVersionInfoString() : "\t- Signature verification failed (" + targetApkFile.getName() + ")";
                logConditionally(logMsg, targetApkFile, !rootTargetFile.equals(targetApkFile), !result.verified);

                if (!result.errors.isEmpty()) for (String e : result.errors) logErr("\t\t" + e);
                if (!result.warnings.isEmpty()) log("\t\t" + result.warnings.size() + " warnings");

                if (result.verified) {
                    for (AndroidApkSignerVerify.CertInfo certInfo : result.certInfoList) {
                        log("\t\t" + certInfo.subjectDn);
                        log("\t\tSHA256: " + certInfo.certSha256);
                    }
                }

                if (checkHashes != null && checkHashes.length > 0) {
                    CertHashChecker.Result certHashResult = new CertHashChecker().check(result, checkHashes);
                    if (certHashResult != null) {
                        if (!certHashResult.verified) logErr("\t\tHash check failed: " + certHashResult.errorString);
                        else log("\t- Hash check successful");
                        return result.verified && certHashResult.verified ? result : null;
                    }
                }
            }
            return result.verified ? result : null;
        } catch (Exception e) {
            logErr("Could not verify: " + e.getMessage());
            return null;
        }
    }

    private static void logErr(String msg) { System.err.println(msg); }
    private static void log(String msg) { System.out.println(msg); }

    private static void logConditionally(String logMsg, File file, boolean appendFile, boolean error) {
        if (appendFile && error) logMsg += " (" + file.getName() + ")";
        if (error) logErr(logMsg); else log(logMsg);
    }

    public static class Result {
        final boolean error;
        final int success;
        final int unsuccessful;
        Result(boolean error, int success, int unsuccessful) { this.error = error; this.success = success; this.unsuccessful = unsuccessful; }
    }
}
