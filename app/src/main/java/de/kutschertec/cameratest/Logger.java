package de.kutschertec.cameratest;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

/**
 * Class that provides logging methods on top of {@link Log}, for better formatting.
 */
public class Logger {
    private static Level globalLogLevel = Level.INFO;

    private Level logLevel;
    private final Class<?> context;

    public enum Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    /**
     * Sets the global log level.
     *
     * @param level the global log level
     */
    public static void setGlobalLogLevel(Level level) {
        globalLogLevel = level;
    }

    /**
     * Returns the global log level.
     *
     * @return the global log level
     */
    public static Level getGlobalLogLevel() {
        return globalLogLevel;
    }

    /**
     * Create a new instance.
     *
     * @param context the context for the logger
     */
    public Logger(@NonNull Object context) {
        this(context.getClass());
    }

    /**
     * Create a new instance.
     *
     * @param context the context for the logger
     */
    public Logger(@NonNull Class<?> context) {
        this.context = context;
        this.logLevel = null;
    }

    /**
     * Sets the log level of this logger overriding the global log level. Set this to
     * <code>null</code> to return to the global log level.
     *
     * @param level the log level of this logger
     */
    public void setLogLevel(@Nullable Level level) {
        this.logLevel = level;
    }

    /**
     * Returns the log level of this logger.
     *
     * @return the log level of this logger
     */
    @NonNull
    public Level getLogLevel() {
        return (logLevel != null) ? logLevel : globalLogLevel;
    }

    private boolean checkLogLevel(@NonNull Level level) {
        return level.ordinal() >= getLogLevel().ordinal();
    }

    @NonNull
    private String formatMessage(@NonNull Object context, @NonNull String message) {
        return formatMessage(message);
    }

    @NonNull
    private String formatMessage(@NonNull String message) {
        StringBuilder msg = new StringBuilder();
        msg.append(context.getCanonicalName());
        msg.append(" - ");
        msg.append(message);
        return msg.toString();
    }

    /**
     * Logs a formatted message with verbose level.
     *
     * @param message the log message
     */
    public void verbose(@NonNull String message) {
        if (checkLogLevel(Level.VERBOSE)) {
            Log.v(CameraTestConstants.APPNAME, formatMessage(context, message));
        }
    }

    /**
     * Logs a formatted message with verbose level.
     *
     * @param message the log message
     * @param t       a throwable that should be logged
     */
    public void verbose(@NonNull String message, @NonNull Throwable t) {
        if (checkLogLevel(Level.VERBOSE)) {
            Log.v(CameraTestConstants.APPNAME, formatMessage(context, message), t);
        }
    }

    /**
     * Logs a formatted message with debug level.
     *
     * @param message the log message
     */
    public void debug(@NonNull String message) {
        if (checkLogLevel(Level.DEBUG)) {
            Log.d(CameraTestConstants.APPNAME, formatMessage(context, message));
        }
    }

    /**
     * Logs a formatted message with debug level.
     *
     * @param message the log message
     * @param t       a throwable that should be logged
     */
    public void debug(@NonNull String message, @NonNull Throwable t) {
        if (checkLogLevel(Level.DEBUG)) {
            Log.d(CameraTestConstants.APPNAME, formatMessage(context, message), t);
        }
    }

    /**
     * Logs a formatted message with info level.
     *
     * @param message the log message
     */
    public void info(@NonNull String message) {
        if (checkLogLevel(Level.INFO)) {
            Log.i(CameraTestConstants.APPNAME, formatMessage(context, message));
        }
    }

    /**
     * Logs a formatted message with info level.
     *
     * @param message the log message
     * @param t       a throwable that should be logged
     */
    public void info(@NonNull String message, @NonNull Throwable t) {
        if (checkLogLevel(Level.INFO)) {
            Log.i(CameraTestConstants.APPNAME, formatMessage(context, message), t);
        }
    }

    /**
     * Logs a formatted message with warn level.
     *
     * @param message the log message
     */
    public void warn(@NonNull String message) {
        if (checkLogLevel(Level.WARN)) {
            Log.w(CameraTestConstants.APPNAME, formatMessage(context, message));
        }
    }

    /**
     * Logs a formatted message with warn level.
     *
     * @param message the log message
     * @param t       a throwable that should be logged
     */
    public void warn(@NonNull String message, @NonNull Throwable t) {
        if (checkLogLevel(Level.WARN)) {
            Log.w(CameraTestConstants.APPNAME, formatMessage(context, message), t);
        }
    }

    /**
     * Logs a formatted message with error level.
     *
     * @param message the log message
     */
    public void error(@NonNull String message) {
        if (checkLogLevel(Level.ERROR)) {
            Log.e(CameraTestConstants.APPNAME, formatMessage(context, message));
        }
    }

    /**
     * Logs a formatted message with error level.
     *
     * @param message the log message
     * @param t       a throwable that should be logged
     */
    public void error(@NonNull String message, @NonNull Throwable t) {
        if (checkLogLevel(Level.ERROR)) {
            Log.e(CameraTestConstants.APPNAME, formatMessage(context, message), t);
        }
    }
}
