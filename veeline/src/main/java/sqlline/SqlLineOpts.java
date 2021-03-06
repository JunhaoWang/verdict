/*
 * Copyright 2017 University of Michigan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Modified BSD License
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at:
//
// http://opensource.org/licenses/BSD-3-Clause
 */
package sqlline;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import jline.TerminalFactory;
import jline.console.completer.Completer;
import jline.console.completer.StringsCompleter;

/**
 * Session options.
 */
class SqlLineOpts implements Completer {
    
    public static final String PROPERTY_PREFIX = "sqlline.";
    public static final String PROPERTY_NAME_EXIT =
            PROPERTY_PREFIX + "system.exit";
    private SqlLine sqlLine;
    private boolean autoSave = false;
    private boolean silent = false;
    private boolean color = false;
    private boolean showHeader = true;
    private int headerInterval = 100;
    private boolean fastConnect = true;
    private boolean autoCommit = true;
    private boolean verbose = false;
    private boolean force = false;
    private boolean incremental = false;
    private boolean showElapsedTime = true;
    private boolean showWarnings = false;
    private boolean showNestedErrs = false;
    private String numberFormat = "default";
//    private int maxWidth = TerminalFactory.get().getWidth();
    private int maxWidth = 2000;
//    private int maxHeight = TerminalFactory.get().getHeight();
    private int maxHeight = 2000;
    private int maxColumnWidth = 100;
    int rowLimit = 0;
    int timeout = -1;
    private String isolation = "TRANSACTION_REPEATABLE_READ";
    private String outputFormat = "table";
    private boolean trimScripts = true;
    private File rcFile = new File(saveDir(), "sqlline.properties");
    private String historyFile =
            new File(saveDir(), "history").getAbsolutePath();
    private String runFile;

    public SqlLineOpts(SqlLine sqlLine) {
        this.sqlLine = sqlLine;
    }

    public SqlLineOpts(SqlLine sqlLine, Properties props) {
        this(sqlLine);
        loadProperties(props);
    }

    public List<Completer> optionCompleters() {
        return Collections.<Completer>singletonList(this);
    }

    public List<String> possibleSettingValues() {
        return Arrays.asList("yes", "no");
    }

    /**
     * The save directory if HOME/.sqlline/ on UNIX, and HOME/sqlline/ on
     * Windows.
     */
    public File saveDir() {
        String dir = System.getProperty("sqlline.rcfile");
        if (dir != null && dir.length() > 0) {
            return new File(dir);
        }

        String baseDir = System.getProperty(SqlLine.SQLLINE_BASE_DIR);
        if (baseDir != null && baseDir.length() > 0) {
            File saveDir = new File(baseDir).getAbsoluteFile();
            saveDir.mkdirs();
            return saveDir;
        }

        File f =
                new File(
                        System.getProperty("user.home"),
                        ((System.getProperty("os.name").toLowerCase()
                                .indexOf("windows") != -1) ? "" : ".") + "sqlline")
                .getAbsoluteFile();
        try {
            f.mkdirs();
        } catch (Exception e) {
            // ignore
        }

        return f;
    }

    public int complete(String buf, int pos, List<CharSequence> candidates) {
        try {
            return new StringsCompleter(propertyNames())
                    .complete(buf, pos, candidates);
        } catch (Throwable t) {
            return -1;
        }
    }

    public void save() throws IOException {
        OutputStream out = new FileOutputStream(rcFile);
        save(out);
        out.close();
    }

    public void save(OutputStream out) throws IOException {
        try {
            Properties props = toProperties();

            // don't save maxwidth: it is automatically set based on
            // the terminal configuration
            props.remove(PROPERTY_PREFIX + "maxwidth");

            props.store(out, sqlLine.getApplicationTitle());
        } catch (Exception e) {
            sqlLine.handleException(e);
        }
    }

    Set<String> propertyNames()
            throws IllegalAccessException, InvocationTargetException {
        final TreeSet<String> set = new TreeSet<String>();
        for (String s : propertyNamesMixed()) {
            set.add(s.toLowerCase());
        }
        return set;
    }

    Set<String> propertyNamesMixed()
            throws IllegalAccessException, InvocationTargetException {
        TreeSet<String> names = new TreeSet<String>();

        // get all the values from getXXX methods
        for (Method method : getClass().getDeclaredMethods()) {
            if (!method.getName().startsWith("get")) {
                continue;
            }

            if (method.getParameterTypes().length != 0) {
                continue;
            }

            String propName = deCamel(method.getName().substring(3));
            if (propName.equals("run")) {
                // Not a real property
                continue;
            }
            if (propName.equals("autosave")) {
                // Deprecated; property is now "autoSave"
                continue;
            }
            names.add(propName);
        }

        return names;
    }

    /** Converts "CamelCase" to "camelCase". */
    private static String deCamel(String s) {
        return s.substring(0, 1).toLowerCase()
                + s.substring(1);
    }

    public Properties toProperties()
            throws IllegalAccessException,
            InvocationTargetException,
            ClassNotFoundException {
        Properties props = new Properties();

        for (String name : propertyNames()) {
            props.setProperty(PROPERTY_PREFIX + name,
                    sqlLine.getReflector().invoke(this, "get" + name).toString());
        }

        sqlLine.debug("properties: " + props.toString());
        return props;
    }

    public void load() throws IOException {
        if (rcFile.exists()) {
            InputStream in = new FileInputStream(rcFile);
            load(in);
            in.close();
        }
    }

    public void load(InputStream fin) throws IOException {
        Properties p = new Properties();
        p.load(fin);
        loadProperties(p);
    }

    public void loadProperties(Properties props) {
        for (String key : Commands.asMap(props).keySet()) {
            if (key.equals(PROPERTY_NAME_EXIT)) {
                // fix for sf.net bug 879422
                continue;
            }
            if (key.startsWith(PROPERTY_PREFIX)) {
                set(key.substring(PROPERTY_PREFIX.length()), props.getProperty(key));
            }
        }
    }

    public void set(String key, String value) {
        set(key, value, false);
    }

    public boolean set(String key, String value, boolean quiet) {
        try {
            sqlLine.getReflector().invoke(this, "set" + key, value);
            return true;
        } catch (Exception e) {
            if (!quiet) {
                // need to use System.err here because when bad command args
                // are passed this is called before init is done, meaning
                // that sqlline's error() output chokes because it depends
                // on properties like text coloring that can get set in
                // arbitrary order.
                System.err.println(sqlLine.loc("error-setting", key, e));
            }
            return false;
        }
    }

    public void setFastConnect(boolean fastConnect) {
        this.fastConnect = fastConnect;
    }

    public boolean getFastConnect() {
        return this.fastConnect;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public boolean getAutoCommit() {
        return this.autoCommit;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean getVerbose() {
        return this.verbose;
    }

    public void setShowElapsedTime(boolean showElapsedTime) {
        this.showElapsedTime = showElapsedTime;
    }

    public boolean getShowElapsedTime() {
        return this.showElapsedTime;
    }

    public void setShowWarnings(boolean showWarnings) {
        this.showWarnings = showWarnings;
    }

    public boolean getShowWarnings() {
        return this.showWarnings;
    }

    public void setShowNestedErrs(boolean showNestedErrs) {
        this.showNestedErrs = showNestedErrs;
    }

    public boolean getShowNestedErrs() {
        return this.showNestedErrs;
    }

    public void setNumberFormat(String numberFormat) {
        this.numberFormat = numberFormat;
    }

    public String getNumberFormat() {
        return this.numberFormat;
    }

    public void setMaxWidth(int maxWidth) {
        this.maxWidth = maxWidth;
    }

    public int getMaxWidth() {
        return this.maxWidth;
    }

    public void setMaxColumnWidth(int maxColumnWidth) {
        this.maxColumnWidth = maxColumnWidth;
    }

    public int getMaxColumnWidth() {
        return this.maxColumnWidth;
    }

    public void setRowLimit(int rowLimit) {
        this.rowLimit = rowLimit;
    }

    public int getRowLimit() {
        return this.rowLimit;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getTimeout() {
        return this.timeout;
    }

    public void setIsolation(String isolation) {
        this.isolation = isolation;
    }

    public String getIsolation() {
        return this.isolation;
    }

    public void setHistoryFile(String historyFile) {
        this.historyFile = historyFile;
    }

    public String getHistoryFile() {
        return this.historyFile;
    }

    public void setColor(boolean color) {
        this.color = color;
    }

    public boolean getColor() {
        return this.color;
    }

    public void setShowHeader(boolean showHeader) {
        this.showHeader = showHeader;
    }

    public boolean getShowHeader() {
        return this.showHeader;
    }

    public void setHeaderInterval(int headerInterval) {
        this.headerInterval = headerInterval;
    }

    public int getHeaderInterval() {
        return this.headerInterval;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public boolean getForce() {
        return this.force;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    public boolean getIncremental() {
        return this.incremental;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
    }

    public boolean getSilent() {
        return this.silent;
    }

    /** @deprecated Use {@link #setAutoSave(boolean)} */
    @Deprecated
    public void setAutosave(boolean autoSave) {
        setAutoSave(autoSave);
    }

    /** @deprecated Use {@link #getAutoSave()} */
    @Deprecated
    public boolean getAutosave() {
        return getAutoSave();
    }

    public void setAutoSave(boolean autoSave) {
        this.autoSave = autoSave;
    }

    public boolean getAutoSave() {
        return this.autoSave;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public String getOutputFormat() {
        return this.outputFormat;
    }

    public void setTrimScripts(boolean trimScripts) {
        this.trimScripts = trimScripts;
    }

    public boolean getTrimScripts() {
        return this.trimScripts;
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }

    public int getMaxHeight() {
        return this.maxHeight;
    }

    public File getPropertiesFile() {
        return rcFile;
    }

    public void setRun(String runFile) {
        this.runFile = runFile;
    }

    public String getRun() {
        return this.runFile;
    }
}

// End SqlLineOpts.java
