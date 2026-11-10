import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves Jira tickets named by a merged pull request.
 * develop becomes "In Development". master becomes "Ready for test".
 */
public class JiraTransition {

    static final Pattern KEY = Pattern.compile("\\b[A-Z][A-Z0-9]+-\\d+\\b");
    static final String IN_DEVELOPMENT = "In Development";
    static final String READY_FOR_TEST = "Ready for test";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-check".equals(args[0])) {
            selfCheck();
            System.out.println("Self-check passed.");
            return;
        }

        String baseUrl = trimSlash(required("JIRA_BASE_URL"));
        String email = required("JIRA_EMAIL");
        String token = required("JIRA_API_TOKEN");
        String targetBranch = branchName(required("TARGET_BRANCH"));
        String status = statusFor(targetBranch);

        String text = String.join("\n",
                value("SOURCE_BRANCH"),
                value("PR_TITLE"),
                value("PR_BODY"),
                value("COMMIT_SUBJECTS"));
        Set<String> keys = findKeys(text);
        if (keys.isEmpty()) {
            fail("No Jira key found in the source branch, pull request title, body, or commit subjects.");
        }

        System.out.println("Target branch: " + targetBranch);
        System.out.println("Target status: " + status);
        System.out.println("Ticket keys: " + String.join(", ", keys));

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (email + ":" + token).getBytes(StandardCharsets.UTF_8));

        for (String key : keys) {
            moveTicket(client, baseUrl, authorization, key, status);
        }
    }

    static void moveTicket(HttpClient client, String baseUrl, String authorization, String key, String targetStatus)
            throws Exception {
        String issueUrl = baseUrl + "/rest/api/3/issue/" + key;
        String issueBody = send(client, authorization, "GET", issueUrl + "?fields=status", null);
        String currentStatus = textAt(Json.parse(issueBody), "fields", "status", "name");
        if (targetStatus.equals(currentStatus)) {
            System.out.println(key + " is already " + targetStatus + ".");
            return;
        }

        String transitionsBody = send(client, authorization, "GET", issueUrl + "/transitions", null);
        String transitionId = null;
        List<String> available = new ArrayList<>();
        Object parsed = Json.parse(transitionsBody);
        Object transitions = child(parsed, "transitions");
        if (transitions instanceof List<?> list) {
            for (Object item : list) {
                String destination = textAt(item, "to", "name");
                if (destination != null) {
                    available.add(destination);
                }
                if (targetStatus.equals(destination)) {
                    transitionId = scalar(child(item, "id"));
                }
            }
        }
        if (transitionId == null) {
            fail("No workflow arrow leads " + key + " to \"" + targetStatus + "\"."
                    + " Current status: " + currentStatus
                    + ". Available destinations: " + (available.isEmpty() ? "(none)" : String.join(", ", available)));
        }

        send(client, authorization, "POST", issueUrl + "/transitions",
                "{\"transition\":{\"id\":\"" + transitionId + "\"}}");
        System.out.println(key + " moved from " + currentStatus + " to " + targetStatus + ".");
    }

    static String send(HttpClient client, String authorization, String method, String url, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authorization)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code == 401 || code == 403) {
            fail("Jira rejected the email or API token (HTTP " + code + ").");
        }
        if (code / 100 != 2) {
            fail("Jira returned HTTP " + code + " for " + method + " " + url + ": " + response.body());
        }
        return response.body() == null ? "" : response.body();
    }

    static String statusFor(String branch) {
        return switch (branch) {
            case "develop" -> IN_DEVELOPMENT;
            case "master" -> READY_FOR_TEST;
            default -> fail("This workflow only moves tickets for develop and master. Branch was: " + branch);
        };
    }

    static String branchName(String ref) {
        String name = ref.trim();
        if (name.startsWith("refs/heads/")) {
            name = name.substring("refs/heads/".length());
        }
        return name;
    }

    static Set<String> findKeys(String text) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = KEY.matcher(text == null ? "" : text);
        while (matcher.find()) {
            keys.add(matcher.group());
        }
        return keys;
    }

    static String required(String name) {
        String found = value(name);
        if (found.isBlank()) {
            fail("Missing " + name + ". Add it as a GitHub Actions secret.");
        }
        return found;
    }

    static String value(String name) {
        String found = System.getenv(name);
        return found == null ? "" : found.trim();
    }

    static String trimSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    static Object child(Object node, String field) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        return map.get(field);
    }

    static String textAt(Object node, String... fields) {
        Object current = node;
        for (String field : fields) {
            current = child(current, field);
        }
        return scalar(current);
    }

    static String scalar(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number number) {
            return Long.toString(number.longValue());
        }
        return null;
    }

    static String fail(String message) {
        System.err.println(message);
        System.exit(1);
        return "";
    }

    static void selfCheck() {
        Set<String> keys = findKeys("feature/LAB-1-hello\nLAB-1: sample\nAlso PROJ-42\nskip lab-9");
        if (!keys.equals(Set.of("LAB-1", "PROJ-42"))) {
            throw new IllegalStateException("Keys were " + keys);
        }
        if (!IN_DEVELOPMENT.equals(statusFor(branchName("refs/heads/develop")))) {
            throw new IllegalStateException("develop status");
        }
        if (!READY_FOR_TEST.equals(statusFor("master"))) {
            throw new IllegalStateException("master status");
        }

        Object issue = Json.parse("{\"fields\":{\"status\":{\"name\":\"To Do\"}}}");
        if (!"To Do".equals(textAt(issue, "fields", "status", "name"))) {
            throw new IllegalStateException("current status");
        }
        Object transitions = Json.parse("""
                {"transitions":[
                  {"id":"31","name":"Start","to":{"name":"In Development","id":"4"}},
                  {"id":41,"to":{"name":"Ready for test"}}
                ]}
                """);
        Object list = child(transitions, "transitions");
        if (!(list instanceof List<?> items) || items.size() != 2) {
            throw new IllegalStateException("transitions");
        }
        if (!"31".equals(scalar(child(items.get(0), "id")))) {
            throw new IllegalStateException("string id");
        }
        if (!"41".equals(scalar(child(items.get(1), "id")))) {
            throw new IllegalStateException("number id");
        }
        if (!READY_FOR_TEST.equals(textAt(items.get(1), "to", "name"))) {
            throw new IllegalStateException("destination");
        }
    }

    static final class Json {
        private final String text;
        private int index;

        static Object parse(String text) {
            Json parser = new Json(text == null ? "" : text);
            Object value = parser.readValue();
            parser.skip();
            if (parser.index != parser.text.length()) {
                throw new IllegalArgumentException("Trailing JSON at index " + parser.index);
            }
            return value;
        }

        Json(String text) {
            this.text = text;
        }

        private Object readValue() {
            skip();
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char current = text.charAt(index);
            if (current == '{') {
                return readObject();
            }
            if (current == '[') {
                return readArray();
            }
            if (current == '"') {
                return readString();
            }
            if (current == 't' || current == 'f') {
                return readLiteral(current == 't' ? "true" : "false", current == 't');
            }
            if (current == 'n') {
                return readLiteral("null", null);
            }
            return readNumber();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skip();
                String key = readString();
                skip();
                expect(':');
                object.put(key, readValue());
                skip();
                if (peek('}')) {
                    index++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skip();
                if (peek(']')) {
                    index++;
                    return list;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return value.toString();
                }
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (index >= text.length()) {
                    throw new IllegalArgumentException("Unfinished escape");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw new IllegalArgumentException("Unfinished unicode escape");
                        }
                        value.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape \\" + escaped);
                }
            }
            throw new IllegalArgumentException("Unfinished string");
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected " + literal);
            }
            index += literal.length();
            return value;
        }

        private Number readNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                decimal = true;
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String literal = text.substring(start, index);
            if (decimal) {
                return Double.valueOf(literal);
            }
            return Long.valueOf(literal);
        }

        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void expect(char expected) {
            skip();
            if (!peek(expected)) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + index);
            }
            index++;
        }
    }
}
