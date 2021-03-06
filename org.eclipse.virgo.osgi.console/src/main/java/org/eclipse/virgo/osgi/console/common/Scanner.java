/*******************************************************************************
 * Copyright (c) 2011 SAP AG
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Lazar Kirchev, SAP AG - initial contribution
 ******************************************************************************/

package org.eclipse.virgo.osgi.console.common;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.virgo.osgi.console.common.ConsoleInputStream;
import org.eclipse.virgo.osgi.console.common.KEYS;
import org.eclipse.virgo.osgi.console.telnet.ANSITerminalTypeMappings;
import org.eclipse.virgo.osgi.console.telnet.SCOTerminalTypeMappings;
import org.eclipse.virgo.osgi.console.telnet.TerminalTypeMappings;
import org.eclipse.virgo.osgi.console.telnet.VT100TerminalTypeMappings;
import org.eclipse.virgo.osgi.console.telnet.VT220TerminalTypeMappings;
import org.eclipse.virgo.osgi.console.telnet.VT320TerminalTypeMappings;

/**
 * A common superclass for content processor for the telnet protocol and for command line editing (processing delete,
 * backspace, arrows, command history, etc.).
 */
public abstract class Scanner {

    protected static final byte BS = 8;

    private byte BACKSPACE;

    protected static final byte LF = 10;

    protected static final byte CR = 13;

    protected static final byte ESC = 27;

    protected static final byte SPACE = 32;

    private byte DEL;

    protected static final byte MAX_CHAR = 127;

    protected static final String DEFAULT_TTYPE = File.separatorChar == '/' ? "XTERM" : "ANSI";

    protected OutputStream toTelnet;

    protected ConsoleInputStream toShell;

    protected Map<String, KEYS> currentEscapesToKey;

    protected final Map<String, TerminalTypeMappings> supportedEscapeSequences;

    protected String[] escapes;

    public Scanner(ConsoleInputStream toShell, OutputStream toTelnet) {
        this.toShell = toShell;
        this.toTelnet = toTelnet;
        supportedEscapeSequences = new HashMap<String, TerminalTypeMappings>();
        supportedEscapeSequences.put("ANSI", new ANSITerminalTypeMappings());
        supportedEscapeSequences.put("VT100", new VT100TerminalTypeMappings());
        VT220TerminalTypeMappings vtMappings = new VT220TerminalTypeMappings();
        supportedEscapeSequences.put("VT220", new VT220TerminalTypeMappings());
        supportedEscapeSequences.put("XTERM", vtMappings);
        supportedEscapeSequences.put("VT320", new VT320TerminalTypeMappings());
        supportedEscapeSequences.put("SCO", new SCOTerminalTypeMappings());
    }

    public abstract void scan(int b) throws IOException;

    protected void echo(int b) throws IOException {
        toTelnet.write(b);
    }

    protected void flush() throws IOException {
        toTelnet.flush();
    }

    protected KEYS checkEscape(String possibleEsc) {
        if (currentEscapesToKey.get(possibleEsc) != null) {
            return currentEscapesToKey.get(possibleEsc);
        }

        for (String escape : escapes) {
            if (escape.startsWith(possibleEsc)) {
                return KEYS.UNFINISHED;
            }
        }
        return KEYS.UNKNOWN;
    }

    protected String esc;

    protected boolean isEsc = false;

    protected void startEsc() {
        isEsc = true;
        esc = "";
    }

    protected abstract void scanEsc(final int b) throws IOException;

    public byte getBackspace() {
        return BACKSPACE;
    }

    public void setBackspace(byte backspace) {
        BACKSPACE = backspace;
    }

    public byte getDel() {
        return DEL;
    }

    public void setDel(byte del) {
        DEL = del;
    }

    public Map<String, KEYS> getCurrentEscapesToKey() {
        return currentEscapesToKey;
    }

    public void setCurrentEscapesToKey(Map<String, KEYS> currentEscapesToKey) {
        this.currentEscapesToKey = currentEscapesToKey;
    }

    public String[] getEscapes() {
        if (escapes != null) {
            return Arrays.copyOf(escapes, escapes.length);
        } else {
            return null;
        }
    }

    public void setEscapes(String[] escapes) {
        if (escapes != null) {
            this.escapes = Arrays.copyOf(escapes, escapes.length);
        } else {
            this.escapes = null;
        }
    }

}
