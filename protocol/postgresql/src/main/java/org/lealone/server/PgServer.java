/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.lealone.common.message.DbException;
import org.lealone.common.util.NetUtils;
import org.lealone.common.util.New;
import org.lealone.db.Constants;

/**
 * This class implements a subset of the PostgreSQL protocol as described here:
 * http://developer.postgresql.org/pgdocs/postgres/protocol.html
 * The PostgreSQL catalog is described here:
 * http://www.postgresql.org/docs/7.4/static/catalogs.html
 *
 * @author Thomas Mueller
 * @author Sergi Vladykin 2009-07-03 (convertType)
 * @author zhh
 */
public class PgServer implements Server {

    /**
     * The default port to use for the PG server.
     * This value is also in the documentation and in the Server javadoc.
     */
    public static final int DEFAULT_PORT = 5211;

    /**
     * The VARCHAR type.
     */
    public static final int PG_TYPE_VARCHAR = 1043;

    /**
     * The integer array type (for the column pg_index.indkey).
     */
    public static final int PG_TYPE_INT2VECTOR = 22;

    private static final int PG_TYPE_BOOL = 16;
    private static final int PG_TYPE_BYTEA = 17;
    private static final int PG_TYPE_BPCHAR = 1042;
    private static final int PG_TYPE_INT8 = 20;
    private static final int PG_TYPE_INT2 = 21;
    private static final int PG_TYPE_INT4 = 23;
    private static final int PG_TYPE_TEXT = 25;
    private static final int PG_TYPE_OID = 26;
    private static final int PG_TYPE_FLOAT4 = 700;
    private static final int PG_TYPE_FLOAT8 = 701;
    private static final int PG_TYPE_UNKNOWN = 705;
    private static final int PG_TYPE_TEXTARRAY = 1009;
    private static final int PG_TYPE_DATE = 1082;
    private static final int PG_TYPE_TIME = 1083;
    private static final int PG_TYPE_TIMESTAMP_NO_TMZONE = 1114;
    private static final int PG_TYPE_NUMERIC = 1700;

    private final HashSet<Integer> typeSet = New.hashSet();

    private ServerSocket serverSocket;
    private final Set<PgServerThread> running = Collections.synchronizedSet(new HashSet<PgServerThread>());

    private String listenAddress = Constants.DEFAULT_HOST;
    private int port = PgServer.DEFAULT_PORT;

    private String baseDir;

    private boolean trace;
    private boolean allowOthers;
    private boolean isDaemon;
    private boolean ifExists;

    private boolean stop;

    @Override
    public void init(String... args) {
        for (int i = 0; args != null && i < args.length; i++) {
            String a = args[i];
            if (TcpServer.isOption(a, "-pgListenAddress")) {
                listenAddress = args[++i];
            } else if (TcpServer.isOption(a, "-trace")) {
                trace = true;
            } else if (TcpServer.isOption(a, "-pgPort")) {
                port = Integer.decode(args[++i]);
            } else if (TcpServer.isOption(a, "-baseDir")) {
                baseDir = args[++i];
            } else if (TcpServer.isOption(a, "-pgAllowOthers")) {
                allowOthers = true;
            } else if (TcpServer.isOption(a, "-pgDaemon")) {
                isDaemon = true;
            } else if (TcpServer.isOption(a, "-ifExists")) {
                ifExists = true;
            }
        }
    }

    boolean getTrace() {
        return trace;
    }

    /**
     * Print a message if the trace flag is enabled.
     *
     * @param s the message
     */
    void trace(String s) {
        if (trace) {
            System.out.println(s);
        }
    }

    /**
     * Remove a thread from the list.
     *
     * @param t the thread to remove
     */
    synchronized void remove(PgServerThread t) {
        running.remove(t);
    }

    /**
     * Print the stack trace if the trace flag is enabled.
     *
     * @param e the exception
     */
    void traceError(Exception e) {
        if (trace) {
            e.printStackTrace();
        }
    }

    @Override
    public String getURL() {
        return "pg://" + getListenAddress() + ":" + port;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String getListenAddress() {
        return listenAddress;
    }

    private boolean allow(Socket socket) {
        if (allowOthers) {
            return true;
        }
        try {
            return NetUtils.isLocalAddress(socket);
        } catch (UnknownHostException e) {
            traceError(e);
            return false;
        }
    }

    @Override
    public void start() {
        serverSocket = NetUtils.createServerSocket(listenAddress, port, false);

        String name = getName() + " (" + getURL() + ")";
        Thread t = new Thread(this, name);
        t.setDaemon(isDaemon());
        t.start();
    }

    private void listen() {
        String threadName = Thread.currentThread().getName();
        try {
            while (!stop) {
                Socket s = serverSocket.accept();
                if (!allow(s)) {
                    trace("Connection not allowed");
                    s.close();
                } else {
                    PgServerThread c = createPgServerThread(s);
                    running.add(c);
                    c.setProcessId(running.size());
                    Thread thread = new Thread(c, threadName + " thread");
                    thread.setDaemon(isDaemon);
                    c.setThread(thread);
                    thread.start();
                }
            }
        } catch (Exception e) {
            if (!stop) {
                e.printStackTrace();
            }
        }
    }

    protected PgServerThread createPgServerThread(Socket socket) {
        return new PgServerThread(socket, this);
    }

    @Override
    public void stop() {
        // TODO server: combine with tcp server
        if (!stop) {
            stop = true;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // TODO log exception
                    e.printStackTrace();
                }
                serverSocket = null;
            }
        }
        // TODO server: using a boolean 'now' argument? a timeout?
        for (PgServerThread c : New.arrayList(running)) {
            c.close();
            try {
                Thread t = c.getThread();
                if (t != null) {
                    t.join(100);
                }
            } catch (Exception e) {
                // TODO log exception
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean isRunning(boolean traceError) {
        if (serverSocket == null) {
            return false;
        }
        try {
            Socket s = NetUtils.createLoopbackSocket(serverSocket.getLocalPort(), false);
            s.close();
            return true;
        } catch (Exception e) {
            if (traceError) {
                traceError(e);
            }
            return false;
        }
    }

    public String getBaseDir() {
        return baseDir;
    }

    @Override
    public boolean getAllowOthers() {
        return allowOthers;
    }

    @Override
    public String getType() {
        return "PG";
    }

    @Override
    public String getName() {
        return "PG Server";
    }

    public boolean getIfExists() {
        return ifExists;
    }

    /**
     * The Java implementation of the PostgreSQL function pg_get_indexdef. The
     * method is used to get CREATE INDEX command for an index, or the column
     * definition of one column in the index.
     *
     * @param conn the connection
     * @param indexId the index id
     * @param ordinalPosition the ordinal position (null if the SQL statement
     *            should be returned)
     * @param pretty this flag is ignored
     * @return the SQL statement or the column name
     */
    public static String getIndexColumn(Connection conn, int indexId, Integer ordinalPosition, Boolean pretty)
            throws SQLException {
        if (ordinalPosition == null || ordinalPosition.intValue() == 0) {
            PreparedStatement prep = conn.prepareStatement("select sql from information_schema.indexes where id=?");
            prep.setInt(1, indexId);
            ResultSet rs = prep.executeQuery();
            if (rs.next()) {
                return rs.getString(1);
            }
            return "";
        }
        PreparedStatement prep = conn
                .prepareStatement("select column_name from information_schema.indexes where id=? and ordinal_position=?");
        prep.setInt(1, indexId);
        prep.setInt(2, ordinalPosition.intValue());
        ResultSet rs = prep.executeQuery();
        if (rs.next()) {
            return rs.getString(1);
        }
        return "";
    }

    /**
     * Get the name of the current schema.
     * This method is called by the database.
     *
     * @param conn the connection
     * @return the schema name
     */
    public static String getCurrentSchema(Connection conn) throws SQLException {
        ResultSet rs = conn.createStatement().executeQuery("call schema()");
        rs.next();
        return rs.getString(1);
    }

    /**
     * Get the OID of an object.
     * This method is called by the database.
     *
     * @param conn the connection
     * @param tableName the table name
     * @return the oid
     */
    public static int getOid(Connection conn, String tableName) throws SQLException {
        if (tableName.startsWith("\"") && tableName.endsWith("\"")) {
            tableName = tableName.substring(1, tableName.length() - 1);
        }
        PreparedStatement prep = conn.prepareStatement("select oid from pg_class where relName = ?");
        prep.setString(1, tableName);
        ResultSet rs = prep.executeQuery();
        if (!rs.next()) {
            return 0;
        }
        return rs.getInt(1);
    }

    /**
     * Get the name of this encoding code.
     * This method is called by the database.
     *
     * @param code the encoding code
     * @return the encoding name
     */
    public static String getEncodingName(int code) {
        switch (code) {
        case 0:
            return "SQL_ASCII";
        case 6:
            return "UTF8";
        case 8:
            return "LATIN1";
        default:
            return code < 40 ? "UTF8" : "";
        }
    }

    /**
     * Get the version. This method must return PostgreSQL to keep some clients
     * happy. This method is called by the database.
     *
     * @return the server name and version
     */
    public static String getVersion() {
        return "PostgreSQL 8.1.4  server protocol using H2 " + Constants.getFullVersion();
    }

    /**
     * Get the current system time.
     * This method is called by the database.
     *
     * @return the current system time
     */
    public static Timestamp getStartTime() {
        return new Timestamp(System.currentTimeMillis());
    }

    /**
     * Get the user name for this id.
     * This method is called by the database.
     *
     * @param conn the connection
     * @param id the user id
     * @return the user name
     */
    public static String getUserById(Connection conn, int id) throws SQLException {
        PreparedStatement prep = conn.prepareStatement("SELECT NAME FROM INFORMATION_SCHEMA.USERS WHERE ID=?");
        prep.setInt(1, id);
        ResultSet rs = prep.executeQuery();
        if (rs.next()) {
            return rs.getString(1);
        }
        return null;
    }

    /**
     * Check if the this session has the given database privilege.
     * This method is called by the database.
     *
     * @param id the session id
     * @param privilege the privilege to check
     * @return true
     */
    public static boolean hasDatabasePrivilege(int id, String privilege) {
        return true;
    }

    /**
     * Check if the current session has access to this table.
     * This method is called by the database.
     *
     * @param table the table name
     * @param privilege the privilege to check
     * @return true
     */
    public static boolean hasTablePrivilege(String table, String privilege) {
        return true;
    }

    /**
     * Get the current transaction id.
     * This method is called by the database.
     *
     * @param table the table name
     * @param id the id
     * @return 1
     */
    public static int getCurrentTid(String table, String id) {
        return 1;
    }

    /**
     * Convert the SQL type to a PostgreSQL type
     *
     * @param type the SQL type
     * @return the PostgreSQL type
     */
    public static int convertType(final int type) {
        switch (type) {
        case Types.BOOLEAN:
            return PG_TYPE_BOOL;
        case Types.VARCHAR:
            return PG_TYPE_VARCHAR;
        case Types.CLOB:
            return PG_TYPE_TEXT;
        case Types.CHAR:
            return PG_TYPE_BPCHAR;
        case Types.SMALLINT:
            return PG_TYPE_INT2;
        case Types.INTEGER:
            return PG_TYPE_INT4;
        case Types.BIGINT:
            return PG_TYPE_INT8;
        case Types.DECIMAL:
            return PG_TYPE_NUMERIC;
        case Types.REAL:
            return PG_TYPE_FLOAT4;
        case Types.DOUBLE:
            return PG_TYPE_FLOAT8;
        case Types.TIME:
            return PG_TYPE_TIME;
        case Types.DATE:
            return PG_TYPE_DATE;
        case Types.TIMESTAMP:
            return PG_TYPE_TIMESTAMP_NO_TMZONE;
        case Types.VARBINARY:
            return PG_TYPE_BYTEA;
        case Types.BLOB:
            return PG_TYPE_OID;
        case Types.ARRAY:
            return PG_TYPE_TEXTARRAY;
        default:
            return PG_TYPE_UNKNOWN;
        }
    }

    /**
     * Get the type hash set.
     *
     * @return the type set
     */
    HashSet<Integer> getTypeSet() {
        return typeSet;
    }

    /**
     * Check whether a data type is supported.
     * A warning is logged if not.
     *
     * @param type the type
     */
    void checkType(int type) {
        if (!typeSet.contains(type)) {
            trace("Unsupported type: " + type);
        }
    }

    @Override
    public boolean isDaemon() {
        return isDaemon;
    }

    @Override
    public void run() {
        try {
            listen();
        } catch (Exception e) {
            DbException.traceThrowable(e);
        }
    }
}
