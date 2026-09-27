package com.pablo.apksigner.ui;

import com.pablo.apksigner.SignTool;
import com.pablo.apksigner.util.KeystoreConfigManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class ApkSignerGui {
    protected Shell shell;
    private Display display;

    // UI Components
    private Text textApkInput;
    private Text textOutputDir;
    private Button btnDebugKeystore;
    private Group groupCustomKs;
    private Text textKsPath;
    private Text textKsAlias;
    private Text textKsPass;
    private Text textKeyPass;
    private Button btnV1, btnV2, btnV3, btnV4;
    private Button btnOnlyVerify, btnDryRun, btnSkipZipAlign, btnOverwrite, btnVerbose, btnAllowResign, btnDebug;
    private Text textLineage;
    private StyledText logConsole;
    private Button btnAction;

    // History and Configuration State
    private String lastApkDir;
    private String lastOutputDir;
    private String lastKsDir;
    private File ksConfigFile;
    private KeystoreConfigManager.Config activeKsConfig;
    private Image appIcon;

    private void loadAppIcon() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("icon.png")) {
            if (is != null) {
                appIcon = new Image(display, is);
                shell.setImage(appIcon);

                // Ensure the native OS image resource is disposed when the shell is closed
                shell.addListener(SWT.Dispose, event -> {
                    if (appIcon != null && !appIcon.isDisposed()) {
                        appIcon.dispose();
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    public void open() {
        display = Display.getDefault();

        // Initialize directory paths and configuration file pointer
        String appDir = getAppDirectory();
        lastApkDir = appDir;
        lastOutputDir = appDir;
        lastKsDir = appDir;
        ksConfigFile = new File(appDir, "custom_ksconf.xml");

        createContents();

        // Window centering logic
        Rectangle bounds = display.getPrimaryMonitor().getBounds();
        Rectangle rect = shell.getBounds();
        int x = bounds.x + (bounds.width - rect.width) / 2;
        int y = bounds.y + (bounds.height - rect.height) / 2;
        shell.setLocation(x, y);

        // Redirect System.out and System.err to the GUI console
        redirectSystemStreams();

        // Load custom keystore configurations if present
        loadKeystoreConfig();

        shell.open();
        shell.layout();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }

    protected void createContents() {
        shell = new Shell();
        shell.setSize(750, 700);
        shell.setText("APK Signer Tool");
        shell.setLayout(new GridLayout(1, false));
        loadAppIcon();

        // --- Input/Output Area ---
        Group groupIO = new Group(shell, SWT.NONE);
        groupIO.setText("Input and Output");
        groupIO.setLayout(new GridLayout(3, false));
        groupIO.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 1, 1));

        // APK Input
        new Label(groupIO, SWT.NONE).setText("APK File/Folder:");
        textApkInput = new Text(groupIO, SWT.BORDER);
        textApkInput.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 1, 1));
        registerFileDropTarget(textApkInput, (droppedPath) -> {
            File file = new File(droppedPath);
            lastApkDir = file.isDirectory() ? file.getAbsolutePath() : file.getParent();
        });

        Button btnBrowseApk = new Button(groupIO, SWT.PUSH);
        btnBrowseApk.setText("Browse...");
        btnBrowseApk.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                FileDialog fd = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
                fd.setFilterExtensions(new String[]{"*.apk", "*.*"});
                fd.setFilterPath(resolveDirectory(lastApkDir));
                String file = fd.open();
                if (file != null) {
                    textApkInput.setText(file);
                    File selectedFile = new File(file);
                    lastApkDir = selectedFile.isDirectory() ? selectedFile.getAbsolutePath() : selectedFile.getParent();
                }
            }
        });

        // Output Directory
        new Label(groupIO, SWT.NONE).setText("Output Directory (Optional):");
        textOutputDir = new Text(groupIO, SWT.BORDER);
        textOutputDir.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 1, 1));
        registerFileDropTarget(textOutputDir, (droppedPath) -> {
            File file = new File(droppedPath);
            lastOutputDir = file.isDirectory() ? file.getAbsolutePath() : file.getParent();
        });

        Button btnBrowseOut = new Button(groupIO, SWT.PUSH);
        btnBrowseOut.setText("Browse...");
        btnBrowseOut.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                DirectoryDialog dd = new DirectoryDialog(shell);
                dd.setFilterPath(resolveDirectory(lastOutputDir));
                String path = dd.open();
                if (path != null) {
                    textOutputDir.setText(path);
                    lastOutputDir = path;
                }
            }
        });

        // --- Signing Configuration TabFolder ---
        TabFolder tabFolder = new TabFolder(shell, SWT.NONE);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 1, 1));

        // Tab: Keystore
        TabItem tbtmKeystore = new TabItem(tabFolder, SWT.NONE);
        tbtmKeystore.setText("Keystore Settings");
        Composite compKeystore = new Composite(tabFolder, SWT.NONE);
        tbtmKeystore.setControl(compKeystore);
        compKeystore.setLayout(new GridLayout(1, false));

        btnDebugKeystore = new Button(compKeystore, SWT.CHECK);
        btnDebugKeystore.setText("Use Debug Keystore (Auto-generate or use default)");
        btnDebugKeystore.setSelection(false);
        btnDebugKeystore.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateKeystoreUI();
            }
        });

        groupCustomKs = new Group(compKeystore, SWT.NONE);
        groupCustomKs.setText("Custom Keystore");
        groupCustomKs.setLayout(new GridLayout(3, false));
        groupCustomKs.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 1, 1));

        new Label(groupCustomKs, SWT.NONE).setText("File Path:");
        textKsPath = new Text(groupCustomKs, SWT.BORDER);
        textKsPath.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 1, 1));
        registerFileDropTarget(textKsPath, (droppedPath) -> {
            File file = new File(droppedPath);
            lastKsDir = file.isDirectory() ? file.getAbsolutePath() : file.getParent();
        });

        Button btnBrowseKs = new Button(groupCustomKs, SWT.PUSH);
        btnBrowseKs.setText("Browse...");
        btnBrowseKs.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                FileDialog fd = new FileDialog(shell, SWT.OPEN);
                fd.setFilterExtensions(new String[]{"*.jks", "*.keystore", "*.*"});
                fd.setFilterPath(resolveDirectory(lastKsDir));
                String file = fd.open();
                if (file != null) {
                    textKsPath.setText(file);
                    File selectedFile = new File(file);
                    lastKsDir = selectedFile.isDirectory() ? selectedFile.getAbsolutePath() : selectedFile.getParent();
                }
            }
        });

        new Label(groupCustomKs, SWT.NONE).setText("Keystore Alias:");
        textKsAlias = new Text(groupCustomKs, SWT.BORDER);
        textKsAlias.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(groupCustomKs, SWT.NONE).setText("Keystore Password:");
        textKsPass = new Text(groupCustomKs, SWT.BORDER | SWT.PASSWORD);
        textKsPass.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(groupCustomKs, SWT.NONE).setText("Key Password:");
        textKeyPass = new Text(groupCustomKs, SWT.BORDER | SWT.PASSWORD);
        textKeyPass.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        // Tab: Options
        TabItem tbtmOptions = new TabItem(tabFolder, SWT.NONE);
        tbtmOptions.setText("General Options");
        Composite compOptions = new Composite(tabFolder, SWT.NONE);
        tbtmOptions.setControl(compOptions);
        compOptions.setLayout(new GridLayout(2, false));

        Group groupSchemes = new Group(compOptions, SWT.NONE);
        groupSchemes.setText("Signing Schemes");
        groupSchemes.setLayout(new GridLayout(4, false));
        groupSchemes.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

        btnV1 = new Button(groupSchemes, SWT.CHECK); btnV1.setText("V1 (JAR)"); btnV1.setSelection(true);
        btnV2 = new Button(groupSchemes, SWT.CHECK); btnV2.setText("V2 (Full APK)"); btnV2.setSelection(true);
        btnV3 = new Button(groupSchemes, SWT.CHECK); btnV3.setText("V3 (Rotation)"); btnV3.setSelection(true);
        btnV4 = new Button(groupSchemes, SWT.CHECK); btnV4.setText("V4 (Inc. FS)"); btnV4.setSelection(false);

        Group groupFlags = new Group(compOptions, SWT.NONE);
        groupFlags.setText("Processing Flags");
        groupFlags.setLayout(new GridLayout(2, true));
        groupFlags.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

        btnOnlyVerify = new Button(groupFlags, SWT.CHECK); btnOnlyVerify.setText("Verify Only");
        btnDryRun = new Button(groupFlags, SWT.CHECK); btnDryRun.setText("Dry Run");
        btnSkipZipAlign = new Button(groupFlags, SWT.CHECK); btnSkipZipAlign.setText("Skip ZipAlign");
        btnOverwrite = new Button(groupFlags, SWT.CHECK); btnOverwrite.setText("Overwrite Original File");
        btnVerbose = new Button(groupFlags, SWT.CHECK); btnVerbose.setText("Verbose Output");
        btnAllowResign = new Button(groupFlags, SWT.CHECK); btnAllowResign.setText("Allow Resign"); btnAllowResign.setSelection(true);
        btnDebug = new Button(groupFlags, SWT.CHECK); btnDebug.setText("Debug Mode");

        // Tab: Advanced
        TabItem tbtmAdv = new TabItem(tabFolder, SWT.NONE);
        tbtmAdv.setText("Advanced");
        Composite compAdv = new Composite(tabFolder, SWT.NONE);
        tbtmAdv.setControl(compAdv);
        compAdv.setLayout(new GridLayout(3, false));

        new Label(compAdv, SWT.NONE).setText("Lineage File (V3):");
        textLineage = new Text(compAdv, SWT.BORDER);
        textLineage.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 1, 1));
        Button btnBrowseLineage = new Button(compAdv, SWT.PUSH);
        btnBrowseLineage.setText("Browse...");
        btnBrowseLineage.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                FileDialog fd = new FileDialog(shell, SWT.OPEN);
                fd.setFilterPath(resolveDirectory(lastKsDir));
                String file = fd.open();
                if (file != null) textLineage.setText(file);
            }
        });

        // --- Action Button ---
        Composite compAction = new Composite(shell, SWT.NONE);
        compAction.setLayout(new GridLayout(1, false));
        compAction.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false, 1, 1));

        btnAction = new Button(compAction, SWT.PUSH);
        btnAction.setText("  Start Execution  ");
        btnAction.setFont(new org.eclipse.swt.graphics.Font(display, "Arial", 12, SWT.BOLD));
        GridData gdBtn = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        gdBtn.heightHint = 40;
        btnAction.setLayoutData(gdBtn);
        btnAction.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                runSignTool();
            }
        });

        // --- Console Output ---
        Group groupConsole = new Group(shell, SWT.NONE);
        groupConsole.setText("Console Output");
        groupConsole.setLayout(new GridLayout(1, false));
        groupConsole.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1));

        logConsole = new StyledText(groupConsole, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI | SWT.READ_ONLY);
        logConsole.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        logConsole.setFont(new org.eclipse.swt.graphics.Font(display, "Consolas", 10, SWT.NORMAL));

        updateKeystoreUI();
    }

    private void updateKeystoreUI() {
        boolean isDebug = btnDebugKeystore.getSelection();
        setControlEnabled(groupCustomKs, !isDebug);
    }

    private void setControlEnabled(Composite composite, boolean enabled) {
        composite.setEnabled(enabled);
        for (Control child : composite.getChildren()) {
            if (child instanceof Composite) {
                setControlEnabled((Composite) child, enabled);
            } else {
                child.setEnabled(enabled);
            }
        }
    }

    private void runSignTool() {
        logConsole.setText(""); // Clear console
        btnAction.setEnabled(false);

        // Collect arguments
        final Arg arg = new Arg();

        // Input
        String apkInput = textApkInput.getText().trim();
        if (apkInput.isEmpty()) {
            System.err.println("Error: Please select an APK file.");
            btnAction.setEnabled(true);
            return;
        }
        arg.apkFile = new String[]{apkInput};

        String outDir = textOutputDir.getText().trim();
        if (!outDir.isEmpty()) {
            arg.out = outDir;
        }

        // Flags
        arg.onlyVerify = btnOnlyVerify.getSelection();
        arg.dryRun = btnDryRun.getSelection();
        arg.skipZipAlign = btnSkipZipAlign.getSelection();
        arg.overwrite = btnOverwrite.getSelection();
        arg.verbose = btnVerbose.getSelection();
        arg.allowResign = btnAllowResign.getSelection();
        arg.debug = btnDebug.getSelection();
        arg.v1SchemeEnabled = btnV1.getSelection();
        arg.v2SchemeEnabled = btnV2.getSelection();
        arg.v3SchemeEnabled = btnV3.getSelection();
        arg.v4SchemeEnabled = btnV4.getSelection();
        arg.lineageFilePath = textLineage.getText().trim();
        if (arg.lineageFilePath.isEmpty()) arg.lineageFilePath = null;

        // Keystore
        arg.ksIsDebug = btnDebugKeystore.getSelection();
        if (!arg.ksIsDebug && !arg.onlyVerify) {
            String ksPath = textKsPath.getText().trim();
            String ksAlias = textKsAlias.getText().trim();
            String ksPass = textKsPass.getText().trim();
            String keyPass = textKeyPass.getText().trim();

            if (ksPath.isEmpty() || ksAlias.isEmpty()) {
                System.err.println("Error: Keystore Path and Alias are required when using Custom Keystore.");
                btnAction.setEnabled(true);
                return;
            }

            // Sync updated configuration to XML file
            syncKeystoreConfig(ksPath, ksAlias, ksPass, keyPass);

            // Build SignArgs (Index 0)
            arg.signArgsList = Collections.singletonList(new Arg.SignArgs(0, ksPath, ksAlias, ksPass, keyPass));
        }

        // Execute in a background thread to prevent UI freeze
        new Thread(() -> {
            try {
                SignTool.execute(arg);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                display.asyncExec(() -> {
                    if (!shell.isDisposed()) {
                        btnAction.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private void redirectSystemStreams() {
        try {
            OutputStream out = new OutputStream() {
                private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                @Override
                public synchronized void write(int b) {
                    buffer.write(b);
                    if (b == '\n') {
                        flushBuffer();
                    }
                }

                @Override
                public synchronized void write(byte[] b, int off, int len) {
                    buffer.write(b, off, len);
                    for (int i = off; i < off + len; i++) {
                        if (b[i] == '\n') {
                            flushBuffer();
                            break;
                        }
                    }
                }

                @Override
                public synchronized void flush() {
                    flushBuffer();
                }

                private void flushBuffer() {
                    if (buffer.size() > 0) {
                        String text = buffer.toString(StandardCharsets.UTF_8);
                        buffer.reset();
                        appendLog(text);
                    }
                }
            };

            PrintStream utf8PrintStream = new PrintStream(out, true, StandardCharsets.UTF_8);
            System.setOut(utf8PrintStream);
            System.setErr(utf8PrintStream);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void appendLog(final String text) {
        if (shell.isDisposed()) return;
        display.asyncExec(() -> {
            if (!logConsole.isDisposed()) {
                logConsole.append(text);
                logConsole.setTopIndex(logConsole.getLineCount() - 1);
            }
        });
    }

    /**
     * Enables drop support on a specific Text widget.
     */
    private void registerFileDropTarget(Text textControl, PathCallback callback) {
        DropTarget target = new DropTarget(textControl, DND.DROP_DEFAULT | DND.DROP_COPY | DND.DROP_MOVE);
        target.setTransfer(FileTransfer.getInstance());
        target.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetEvent event) {
                if (event.detail == DND.DROP_DEFAULT) {
                    event.detail = DND.DROP_COPY;
                }
            }

            @Override
            public void drop(DropTargetEvent event) {
                if (FileTransfer.getInstance().isSupportedType(event.currentDataType)) {
                    String[] files = (String[]) event.data;
                    if (files != null && files.length > 0) {
                        textControl.setText(files[0]);
                        if (callback != null) {
                            callback.onPathUpdated(files[0]);
                        }
                    }
                }
            }
        });
    }

    private void loadKeystoreConfig() {
        if (ksConfigFile != null && ksConfigFile.exists()) {
            activeKsConfig = KeystoreConfigManager.load(ksConfigFile);
            if (activeKsConfig != null) {
                if (!activeKsConfig.path.isEmpty()) textKsPath.setText(activeKsConfig.path);
                if (!activeKsConfig.alias.isEmpty()) textKsAlias.setText(activeKsConfig.alias);
                if (!activeKsConfig.keystorePass.isEmpty()) textKsPass.setText(activeKsConfig.keystorePass);
                if (!activeKsConfig.keyPass.isEmpty()) textKeyPass.setText(activeKsConfig.keyPass);
            }
        }
    }

    private void syncKeystoreConfig(String path, String alias, String ksPass, String keyPass) {
        KeystoreConfigManager.Config current = new KeystoreConfigManager.Config(path, alias, ksPass, keyPass);
        if (!current.equals(activeKsConfig)) {
            KeystoreConfigManager.save(ksConfigFile, current);
            activeKsConfig = current;
        }
    }

    private String resolveDirectory(String dir) {
        if (dir != null && !dir.isEmpty()) {
            File f = new File(dir);
            if (f.exists() && f.isDirectory()) {
                return f.getAbsolutePath();
            }
        }
        return getAppDirectory();
    }

    private static String getAppDirectory() {
        try {
            File codeSource = new File(ApkSignerGui.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (codeSource.isFile()) {
                return codeSource.getParentFile().getAbsolutePath();
            }
            return codeSource.getAbsolutePath();
        } catch (Exception e) {
            return System.getProperty("user.dir");
        }
    }

    @FunctionalInterface
    private interface PathCallback {
        void onPathUpdated(String path);
    }
}
