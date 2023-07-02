package loomdbtest;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

public final class SqlScriptUtils {
    private static final String SQL_SCRIPT_DIR_PROPERTY = "loomdbtest.sqlScriptDir";

    private static final Path SQL_SCRIPT_DIR = tryGetSqlScriptDir();

    private static final Configuration FREEMARKER_CONFIG;

    static {
        FREEMARKER_CONFIG = new Configuration(Configuration.VERSION_2_3_32);
        FREEMARKER_CONFIG.setTagSyntax(Configuration.SQUARE_BRACKET_TAG_SYNTAX);
        FREEMARKER_CONFIG.setDefaultEncoding("UTF-8");
        FREEMARKER_CONFIG.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        FREEMARKER_CONFIG.setLogTemplateExceptions(false);
        FREEMARKER_CONFIG.setWrapUncheckedExceptions(false);
        FREEMARKER_CONFIG.setSQLDateAndTimeTimeZone(TimeZone.getDefault());

        try {
            if (SQL_SCRIPT_DIR != null) {
                FREEMARKER_CONFIG.setDirectoryForTemplateLoading(SQL_SCRIPT_DIR.toFile());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tryGetSqlScriptDir() {
        var sqlScriptDirStr = System.getProperty(SQL_SCRIPT_DIR_PROPERTY, "");
        if (sqlScriptDirStr.isEmpty()) {
            return null;
        }

        var result = Paths.get(sqlScriptDirStr);
        return Files.isDirectory(result) ? result : null;
    }

    private static String parseFreemarkerTemplate(
            String name,
            String templateContent,
            Object templateModel
    ) throws IOException {
        var template = new Template(name, templateContent, FREEMARKER_CONFIG);
        var output = new StringWriter();
        try {
            template.process(templateModel, output);
        } catch (TemplateException e) {
            throw new RuntimeException(e);
        }
        return output.toString();
    }

    private static Path getValidSqlScriptDir() {
        if (SQL_SCRIPT_DIR == null) {
            throw new RuntimeException("Invalid " + SQL_SCRIPT_DIR_PROPERTY
                    + ": " + System.getProperty(SQL_SCRIPT_DIR_PROPERTY)
            );
        }
        return SQL_SCRIPT_DIR;
    }

    private static Path tryGetExistingFile(Path baseDir, String... candidates) {
        for (String candidate : candidates) {
            var result = baseDir.resolve(candidate);
            if (Files.isRegularFile(result)) {
                return result;
            }
        }
        return null;
    }

    private static Object toFreemarkerModel(SqlScriptParameters parameters) {
        var result = new HashMap<String, Object>();
        var connection = parameters.connection();
        if (connection != null) {
            result.put("db", new DbUtils(connection));
        }
        result.put("sleep", parameters.sleep());
        result.put("exportedDbUtilsClass", ExportedDbUtils.class.getName());
        return result;
    }

    // Used in Freemarker templates
    @SuppressWarnings("unused")
    public static final class DbUtils {
        private final Connection connection;

        private DbUtils(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        public boolean hasTable(String tableName) throws SQLException {
            try (ResultSet infoRows = connection
                    .getMetaData()
                    .getTables(null, connection.getSchema(), tableName, null)
            ) {
                return infoRows.next();
            }
        }

        public boolean hasFunction(String functionName) throws SQLException {
            try (ResultSet infoRows = connection
                    .getMetaData()
                    .getFunctions(null, connection.getSchema(), functionName)
            ) {
                return infoRows.next();
            }
        }
    }

    public static List<String> loadSqlScriptStatements(
            SqlScriptParameters parameters,
            String dbName,
            String scriptBaseName
    ) throws IOException {
        Path scriptFile = tryGetExistingFile(
                getValidSqlScriptDir().resolve(dbName),
                scriptBaseName + ".sql.ftl",
                scriptBaseName + ".sql"
        );
        if (scriptFile == null) {
            return List.of();
        }

        String script = Files.readString(scriptFile);
        if (scriptFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ftl")) {
            script = parseFreemarkerTemplate(scriptFile.toString(), script, toFreemarkerModel(parameters));
        }
        return splitSqlScript(script);
    }

    private static List<String> splitSqlScript(String sqlScript) {
        var parser = new ScriptParser(sqlScript);
        var result = new ArrayList<String>();

        String statement;
        while ((statement = parser.nextStatement()) != null) {
            String trimmedStatement = statement.trim();
            if (!trimmedStatement.isEmpty()) {
                result.add(trimmedStatement);
            }
        }
        return result;
    }

    private static final class ScriptParser {
        private final String sqlScript;
        private final int sqlScriptLength;
        private final String terminator;
        private int pos;

        public ScriptParser(String sqlScript) {
            this(sqlScript, null);
        }

        public ScriptParser(String sqlScript, String terminator) {
            this.sqlScript = sqlScript;
            this.sqlScriptLength = sqlScript.length();
            this.terminator = terminator;
            this.pos = 0;
        }

        public String nextStatement() {
            return nextStatement(true);
        }

        public String nextStatement(boolean stopAtDelimiter) {
            boolean hasNonSkippedChars = false;
            int startPos = pos;
            boolean skippedSeparatorPrev = true;
            while (pos < sqlScriptLength) {
                if (stopAtDelimiter && sqlScript.charAt(pos) == ';') {
                    String statement = hasNonSkippedChars
                            ? sqlScript.substring(startPos, pos)
                            : "";
                    pos++;
                    return statement;
                }

                if (terminator != null && skipCaseInsensitive(terminator)) {
                    break;
                }

                if (skippedSeparatorPrev) {
                    if (skipQuoted() || skipNestable()) {
                        continue;
                    }
                }

                skippedSeparatorPrev = skipNonStatementCharacters();
                if (!skippedSeparatorPrev) {
                    hasNonSkippedChars = true;
                    pos++;
                }
            }
            return stopAtDelimiter && hasNonSkippedChars
                    ? sqlScript.substring(startPos)
                    : null;
        }

        private boolean skipNestable() {
            return skipNestable("begin", "end");
        }

        private boolean skipNestable(String start, String end) {
            if (!skipCaseInsensitive(start)) {
                return false;
            }

            var childParser = new ScriptParser(sqlScript, end);
            childParser.pos = pos;
            childParser.nextStatement(false);
            pos = childParser.pos;
            return true;
        }

        private boolean skipQuoted() {
            return skipQuoted('\'')
                    || skipQuoted('"')
                    || skipQuoted('`')
                    || skipSpecialQuote();
        }

        private boolean skipSpecialQuote() {
            int startPos = pos;
            if (!skipCaseInsensitive("q'")) {
                return false;
            }

            if (pos < sqlScriptLength) {
                char specialQuote = sqlScript.charAt(pos);
                if (Character.isWhitespace(specialQuote)) {
                    pos = startPos;
                    return false;
                }
                pos++;
                skipTerminator(toClosingQuote(specialQuote) + "'");
            }
            return true;
        }

        private static char toClosingQuote(char ch) {
            return switch (ch) {
                case '(' -> ')';
                case '[' -> ']';
                case '{' -> '}';
                case '<' -> '>';
                default -> ch;
            };
        }

        private boolean skipCaseInsensitive(String str) {
            int strLength = str.length();
            if (pos >= sqlScriptLength - strLength) {
                return false;
            }

            int offset = pos;
            for (int i = 0; i < strLength; i++) {
                if (Character.toLowerCase(sqlScript.charAt(i + offset)) != Character.toLowerCase(str.charAt(i))) {
                    return false;
                }
            }
            pos += strLength;
            return true;
        }

        private boolean skipQuoted(char quote) {
            if (sqlScript.charAt(pos) != quote) {
                return false;
            }

            pos++;
            boolean prevEscape = false;
            for (; pos < sqlScriptLength; pos++) {
                if (prevEscape) {
                    prevEscape = false;
                    continue;
                }

                char ch = sqlScript.charAt(pos);
                if (ch == quote) {
                    pos++;
                    return true;
                }
                prevEscape = ch == '\\';
            }
            // We will let the SQL engine deal with unterminated quotes.
            return true;
        }

        private boolean skipNonStatementCharacters() {
            return skipWhiteSpace()
                    || skipLineComment("--")
                    || skipBlock("/*", "*/");
        }

        private boolean skipWhiteSpace() {
            return skipUntil(ch -> !Character.isWhitespace(ch));
        }

        private boolean skipLineComment(String open) {
            if (!sqlScript.startsWith(open, pos)) {
                return false;
            }

            pos += open.length();
            // We are not skipping the line ending, but it doesn't matter,
            // because we still progressed the position, and the line ending
            // characters will be just skipped in the next iteration.
            skipUntil(ScriptParser::isLineEnding);
            return true;
        }

        private boolean skipBlock(String open, String close) {
            if (!sqlScript.startsWith(open, pos)) {
                return false;
            }

            pos += open.length();
            skipTerminator(close);
            return true;
        }


        private static boolean isLineEnding(char ch) {
            return ch == '\n' || ch == '\r';
        }

        private void skipTerminator(String terminator) {
            if (terminator.isEmpty()) {
                throw new IllegalArgumentException("Terminator must not be empty");
            }

            char firstCh = terminator.charAt(0);
            while (pos < sqlScriptLength) {
                if (sqlScript.startsWith(terminator, pos)) {
                    pos += terminator.length();
                    return;
                }
                skipUntil(ch -> ch == firstCh);
            }
        }

        private boolean skipUntil(CharPredicate predicate) {
            int start = pos;
            for (; pos < sqlScriptLength; pos++) {
                char ch = sqlScript.charAt(pos);
                if (predicate.test(ch)) {
                    break;
                }
            }
            return start != pos;
        }
    }

    private interface CharPredicate {
        boolean test(char ch);
    }

    private SqlScriptUtils() {
        throw new AssertionError();
    }
}
