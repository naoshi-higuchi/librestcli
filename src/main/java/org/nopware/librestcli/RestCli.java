package org.nopware.librestcli;

import com.google.common.base.Strings;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.utils.URIBuilder;
import org.nopware.librestcli.RestCli.Authorization.None;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.*;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Slf4j
public class RestCli {
    /**
     * Authorization.
     */
    public sealed interface Authorization permits Authorization.None, Authorization.AuthorizationHeader, Authorization.UsernameAndPasswordInUriAuthority {
        /**
         * No authorization.
         */
        record None() implements Authorization {
        }

        /**
         * Authorization header in HTTP request header.
         * <p>
         * Token example: {@literal Authorization authorization = new Authorization.AuthorizationHeader("token 1234567890abcdef1234567890abcdef12345678");}
         * <p></p>
         * Bearer example: {@literal Authorization authorization = new Authorization.AuthorizationHeader("Bearer 1234567890abcdef1234567890abcdef12345678");}
         */
        record AuthorizationHeader(@NonNull String authorizationHeader) implements Authorization {
        }

        /**
         * Username and password in URI authority.
         * <p>
         * example: <pre>{@literal https://username:password@host/path}</pre>
         */
        record UsernameAndPasswordInUriAuthority(@NonNull String username,
                                                        @NonNull String password) implements Authorization {
        }
    }

    /**
     * Specification of RestCli.<br>
     * RestCliSpec object is created from specified OpenAPI specification, and defines the behavior of your rest client.
     * <p>
     * Create it by {@link #createRestCliSpec(String, String)}.<br>
     * Use it by {@link #execute(RestCliSpec, String...)}.<br>
     * <p>
     * RestCliSpec object is immutable.
     * Reuse the object as much as possible.
     */
    public static class RestCliSpec {
        // `delombok` by lombok-maven-plugin does not work with Java 21 now. So, I use the old style for generating appropriate javadoc.
        // `record` is not good for RestCliSpec because it must be a public class, but it should have private accessors.
        private final CommandSpec commandSpec;
        private final OpenAPI openAPI;

        private RestCliSpec(@NonNull CommandSpec commandSpec, @NonNull OpenAPI openAPI) {
            this.commandSpec = commandSpec;
            this.openAPI = openAPI;
        }

        public int hashCode() {
            return Objects.hash(commandSpec, openAPI);
        }

        public String toString() {
            return "RestCli.RestCliSpec(commandSpec=" + this.commandSpec + ", openAPI=" + this.openAPI + ")";
        }

        public boolean equals(final Object o) {
            if (o == this) return true;
            if (!(o instanceof RestCliSpec)) return false;

            RestCliSpec other = (RestCliSpec) o;

            return Objects.equals(commandSpec, other.commandSpec) &&
                    Objects.equals(openAPI, other.openAPI);
        }
    }

    /**
     * PathMatcher.
     * <p>
     *     PathMatcher is used to customize the behavior of RestCli.
     *     For example, you can specify the path to which the option is applied.
     *     <p>
     *         PathMatcher is applied to the path before the path parameters are resolved.
     *         For example, if the path is {@literal /repos/{owner}/{repo}/issues}, PathMatcher is applied to {@literal /repos/{owner}/{repo}/issues}, not to {@literal /repos/naoshi-higuchi/librestcli/issues}.
     */
    public sealed interface PathMatcher permits PathMatcher.AllMatcher, PathMatcher.StringMatcher, PathMatcher.GlobMatcher, PathMatcher.RegexMatcher, PathMatcher.MatcherWithException, PathMatcher.CustomMatcher {
        boolean matches(@NonNull String path);

        /**
         * Match all paths.
         * @return
         */
        static PathMatcher all() {
            return new AllMatcher();
        }

        /**
         * Match the specified path.
         * @param path
         * @return
         */
        static PathMatcher string(@NonNull String path) {
            return new StringMatcher(path);
        }

        /**
         * Match by glob pattern.
         * @param globPattern
         * @return
         */
        static PathMatcher glob(@NonNull String globPattern) {
            return new GlobMatcher(globPattern);
        }

        /**
         * Match by regular expression.
         * @param regex
         * @return
         */
        static PathMatcher regex(@NonNull String regex) {
            return new RegexMatcher(regex);
        }

        /**
         * Match by custom predicate.
         * @param pathPredicate
         * @return
         */
        static PathMatcher custom(@NonNull Predicate<String> pathPredicate) {
            return new CustomMatcher(pathPredicate);
        }

        /**
         * Specify exception. For example: {@literal PathMatcher.all().except(PathMatcher.string("/login"))} matches all paths except {@literal /login}.
         * @param exceptionPathMatcher
         * @return
         */
        default PathMatcher except(@NonNull PathMatcher exceptionPathMatcher) {
            return new MatcherWithException(this, exceptionPathMatcher);
        }

        /**
         * PathMatcher matches all paths.
         */
        final class AllMatcher implements PathMatcher {
            @Override
            public boolean matches(@NonNull String path) {
                return true; // Always true.
            }
        }

        /**
         * PathMatcher matches the specified path.
         */
        record StringMatcher(@NonNull String path) implements PathMatcher {
            @Override
            public boolean matches(@NonNull String path) {
                return this.path.equals(path);
            }
        }

        /**
         * PathMatcher by glob pattern.
         * <p>
         *     It uses {@link java.nio.file.FileSystem#getPathMatcher(String)}.
         */
        final class GlobMatcher implements PathMatcher {
            private final java.nio.file.PathMatcher pathMatcher;

            public GlobMatcher(String globPattern) {
                this.pathMatcher = FileSystems.getDefault().getPathMatcher(String.format("glob:%s", globPattern)); // It may not work on Windows.
            }

            @Override
            public boolean matches(@NonNull String path) {
                return this.pathMatcher.matches(Paths.get(path));
            }
        }

        /**
         * PathMatcher by regular expression.
         * <p>
         *     It uses {@link java.util.regex.Pattern}.
         */
        final class RegexMatcher implements PathMatcher {
            private final Pattern pattern;

            public RegexMatcher(String regex) {
                this.pattern = Pattern.compile(regex);
            }

            @Override
            public boolean matches(@NonNull String path) {
                return this.pattern.matcher(path).matches();
            }
        }

        /**
         * PathMatcher with exception.
         * <p>
         *     It matches the path if the path matches {@code pathMatcher} and does not match {@code exceptionPathMatcher}.
         */
        final class MatcherWithException implements PathMatcher {
            private final PathMatcher pathMatcher;
            private final PathMatcher exceptionPathMatcher;

            public MatcherWithException(@NonNull PathMatcher pathMatcher, @NonNull PathMatcher exceptionPathMatcher) {
                this.pathMatcher = pathMatcher;
                this.exceptionPathMatcher = exceptionPathMatcher;
            }

            @Override
            public boolean matches(@NonNull String path) {
                return pathMatcher.matches(path) && !exceptionPathMatcher.matches(path);
            }
        }

        /**
         * PathMatcher by custom predicate.
         */
        final class CustomMatcher implements PathMatcher {
            private final Predicate<String> pathPredicate;

            public CustomMatcher(@NonNull Predicate<String> pathPredicate) {
                this.pathPredicate = pathPredicate;
            }
            @Override
            public boolean matches(@NonNull String path) {
                return this.pathPredicate.test(path);
            }
        }
    }

    public static class OptionAppender {
        @FunctionalInterface
        public interface Appender {
            void accept(List<String> options);
        }
        private final PathMatcher pathMatcher;
        private final Set<String> operations;
        private final OptionAppender.Appender appender;

        /**
         * Create OptionAppender.
         *
         * @param pathMatcher  PathMatcher object.
         * @param operations   Set of operations. operation is one of get, head, post, put, delete, options, trace, patch.
         * @param appender     Append options to options.
         */
        public OptionAppender(@NonNull PathMatcher pathMatcher, @NonNull Set<String> operations, @NonNull OptionAppender.Appender appender) {
            this.pathMatcher = pathMatcher;
            this.operations = operations;
            this.appender = appender;
        }

        Optional<List<String>> getOptions(String path, String operation) {
            List<String> options = new LinkedList<>();
            if (pathMatcher.matches(path) && operations.contains(operation)) {
                appender.accept(options);
                return Optional.of(options);
            } else {
                return Optional.empty();
            }
        }
    }

    public static class HeaderAppender {
        @FunctionalInterface
        public interface Appender {
            void accept(Multimap<String, String> headers);
        }
        private final PathMatcher pathMatcher;
        private final Set<String> operations;
        private final Appender appender;

        /**
         * Create HeaderAppender.
         *
         * @param pathMatcher  PathMatcher object.
         * @param operations   Set of operations. operation is one of get, head, post, put, delete, options, trace, patch.
         * @param appender     Append header to headers.
         */
        public HeaderAppender(@NonNull PathMatcher pathMatcher, @NonNull Set<String> operations, @NonNull RestCli.HeaderAppender.Appender appender) {
            this.pathMatcher = pathMatcher;
            this.operations = operations;
            this.appender = appender;
        }

        Optional<Multimap<String, String>> getHeaders(String path, String operation) {
            Multimap<String, String> headers = LinkedListMultimap.create();
            if (pathMatcher.matches(path) && operations.contains(operation)) {
                appender.accept(headers);
                return Optional.of(headers);
            } else {
                return Optional.empty();
            }
        }
    }

    private final CommandLine commandLine;
    private final OpenAPI openAPI;
    private final Authorization authorization;
    private static final OptionSpec generateBashAutoCompletionScriptOption = OptionSpec.builder("--generate-bash-auto-completion-script")
            .required(false)
            .arity("0..1")
            .description("Generate Bash auto completion script and exit.")
            .paramLabel("file")
            .type(String.class)
            .build();

    private static final OptionSpec requestBodyOptionSpec = OptionSpec.builder("--request-body")
            .required(false)
            .arity("1")
            .description("Request body.")
            .paramLabel("body")
            .type(String.class)
            .build();

    private static final OptionSpec stdinOptionSpec = OptionSpec.builder("--stdin")
            .required(false)
            .arity("0")
            .description("Read request body from stdin.")
            .type(String.class)
            .build();
    private static final OptionSpec inputFileOptionSpec = OptionSpec.builder("--input-file")
            .required(false)
            .arity("1")
            .description("Input file. If not specified, the request body is empty.")
            .paramLabel("file")
            .type(String.class)
            .build();

    private static final CommandLine.Model.ArgGroupSpec requestBodyArgGroupSpec = CommandLine.Model.ArgGroupSpec.builder()
            .exclusive(true)
            .addArg(requestBodyOptionSpec)
            .addArg(stdinOptionSpec)
            .addArg(inputFileOptionSpec)
            .build();
    private static final OptionSpec outputFileOptionSpec = OptionSpec.builder("--output-file")
            .required(false)
            .arity("1")
            .description("Output file. If not specified, the response is printed to stdout.")
            .paramLabel("file")
            .type(String.class)
            .build();

    private static final OptionSpec assertHttpStatusCodeSpec = OptionSpec.builder("--assert-http-status-code", "--sc")
            .required(false)
            .arity("1")
            .description("Assert HTTP status code. If the status code is not equal to the specified value, exit with non-zero status code.")
            .paramLabel("http-status-code")
            .type(Integer.class)
            .defaultValue("200")
            .build();

    private RestCli(@NonNull RestCliSpec restCliSpec, @NonNull Authorization authorization) {
        this.commandLine = new CommandLine(restCliSpec.commandSpec);
        this.commandLine.setExecutionStrategy(this::doExecute);
        this.openAPI = restCliSpec.openAPI;
        this.authorization = authorization;
    }

    private RestCli(@NonNull RestCliSpec restCliSpec, @NonNull Authorization authorization, PrintWriter commandLineOut, PrintWriter commandLineErr) {
        this.commandLine = new CommandLine(restCliSpec.commandSpec);
        this.commandLine.setExecutionStrategy(this::doExecute);
        this.commandLine.setOut(commandLineOut);
        this.commandLine.setErr(commandLineErr);
        this.openAPI = restCliSpec.openAPI;
        this.authorization = authorization;
    }

    private int generateBashAutoCompletionScript(CommandLine.ParseResult parseResult) {
        String bash = AutoComplete.bash(parseResult.commandSpec().name(), this.commandLine);

        String autoCompletionFile = parseResult.matchedOptionValue(generateBashAutoCompletionScriptOption.longestName(), "The default value never used.");
        if (Strings.isNullOrEmpty(autoCompletionFile)) { // If the option is specified without a value, `autoCompletionFile` is empty string (""), not null nor default value.
            System.out.println(bash);
        } else {
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(autoCompletionFile), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write(bash);
                writer.flush();
            } catch (IOException e) {
                System.err.println(e.getMessage());
                return 1;
            }
        }
        return 0;
    }

    private int execute(String... args) {
        return this.commandLine.execute(args);
    }

    /**
     * Execute the command.
     *
     * @param restCliSpec   {@link RestCliSpec} object. Created by {@link #createRestCliSpec(String, String)}.
     * @param authorization Authorization object.
     * @param args          Command line arguments.
     * @return Exit code.
     */
    public static int execute(RestCliSpec restCliSpec, Authorization authorization, String... args) {
        RestCli restCli = new RestCli(restCliSpec, authorization);
        return restCli.execute(args);
    }

    public static int execute(RestCliSpec restCliSpec, Authorization authorization, PrintWriter commandLineOut, PrintWriter commandLineErr, String... args) {
        RestCli restCli = new RestCli(restCliSpec, authorization, commandLineOut, commandLineErr);
        return restCli.execute(args);
    }

    /**
     * Execute the command without authorization.
     * It is same as {@link #execute(RestCliSpec, Authorization, String...)} with {@link RestCli.Authorization.None}.
     *
     * @param restCliSpec {@link RestCliSpec} object. Created by {@link #createRestCliSpec(String, String)}.
     * @param args        Command line arguments.
     * @return Exit code.
     */
    public static int execute(RestCliSpec restCliSpec, String... args) {
        return execute(restCliSpec, new None(), args);
    }

    private int doExecute(CommandLine.ParseResult parseResult) {
        /*
         * If `--help` option is specified, print the help message and exit.
         * The exit code is 0.
         */
        Integer helpExitCode = CommandLine.executeHelpRequest(parseResult);
        if (helpExitCode != null) {
            return helpExitCode;
        }

        /*
         * If `--generate-bash-auto-completion-script` option is specified, generate the script and exit.
         * The exit code is 0.
         */
        if (parseResult.hasMatchedOption(generateBashAutoCompletionScriptOption)) {
            return generateBashAutoCompletionScript(parseResult);
        }

        /*
         * If no path is specified, print error message and exit.
         */
        List<CommandLine.ParseResult> pathCommands = parseResult.subcommands();
        if (pathCommands.isEmpty()) {
            System.err.println("No path specified.");
            return 1;
        }

        /*
         * If multiple paths are specified, print warning message and use the last path.
         */
        if (pathCommands.size() > 1) {
            log.warn("Multiple paths specified. The last path is used.");
        }

        CommandLine.ParseResult pathCommand = pathCommands.get(pathCommands.size() - 1);

        /*
         * If no method is specified, print error message and exit.
         */
        List<CommandLine.ParseResult> methodCommands = pathCommand.subcommands();
        if (methodCommands.isEmpty()) {
            System.err.println("No method specified.");
            return 1;
        }

        /*
         * If multiple operations are specified, print warning message and use the last operation.
         */
        if (methodCommands.size() > 1) {
            log.warn("Multiple operations specified. The last operation is used.");
        }

        CommandLine.ParseResult methodCommand = methodCommands.get(methodCommands.size() - 1);

        try {
            return doRestRequest(parseResult, pathCommand, methodCommand);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpRequest.BodyPublisher bodyPublisher(CommandLine.ParseResult methodCommand) throws FileNotFoundException {
        String requestBody = methodCommand.matchedOptionValue(requestBodyOptionSpec.longestName(), (String) null); // No default value.
        if (requestBody != null) {
            return HttpRequest.BodyPublishers.ofString(requestBody);
        }

        String inputFilePath = methodCommand.matchedOptionValue(inputFileOptionSpec.longestName(), (String) null); // No default value.
        if (inputFilePath != null) {
            return HttpRequest.BodyPublishers.ofFile(Paths.get(inputFilePath));
        }

        if (methodCommand.hasMatchedOption(stdinOptionSpec)) {
            return HttpRequest.BodyPublishers.ofInputStream(() -> System.in);
        }

        return HttpRequest.BodyPublishers.noBody();
    }

    private int doRestRequest(CommandLine.ParseResult topCommand, CommandLine.ParseResult pathCommand, CommandLine.ParseResult methodCommand) throws FileNotFoundException {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2) // Each request will attempt to upgrade to HTTP/2. If the upgrade fails, then the response will be handled using HTTP/1.1
                .proxy(ProxySelector.getDefault()); // Use the system-wide proxy settings.

        int exitCode = 0;

        try (HttpClient httpClient = httpClientBuilder.build()) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(createUri(pathCommand, methodCommand))
                    .method(
                            methodCommand.commandSpec().name().toUpperCase(), // Do not forget to convert to upper case. It is a pitfall about OpenAPI spec.
                            bodyPublisher(topCommand));

            Multimap<String, String> resolvedHeaders = resolveHeaderParameters(pathCommand.commandSpec().name(), methodCommand.commandSpec().name(), methodCommand);
            resolvedHeaders.forEach(requestBuilder::header);

            switch (authorization) {
                case Authorization.AuthorizationHeader authorizationHeader -> requestBuilder.header("Authorization", authorizationHeader.authorizationHeader());
                default -> {
                }
            }

            String userAgent = String.format("%s/%s", topCommand.commandSpec().name(), String.join(".", topCommand.commandSpec().version()));

            HttpRequest httpRequest = requestBuilder.header("User-Agent", userAgent)
                    .build();

            log.info("Request: {}", httpRequest.toString());
            log.info("URL: {}", httpRequest.uri());
            log.info("Method: {}", httpRequest.method());
            log.info("Headers: {}", httpRequest.headers().map().toString());

            try {
                HttpResponse<InputStream> send = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                log.info("Response code: {}", send.statusCode());
                log.info("ResponseHeaders: {}", send.headers().map().toString());

                if (topCommand.hasMatchedOption(assertHttpStatusCodeSpec)) {
                    Integer expectedStatusCode = topCommand.matchedOptionValue(assertHttpStatusCodeSpec.longestName(), (Integer) null);
                    if (expectedStatusCode != null && expectedStatusCode != send.statusCode()) {
                        log.info("Expected HTTP status code: {}, but got {}.", expectedStatusCode, send.statusCode());
                        exitCode = 1; // Do not return here. Consume the response body.
                    }
                }

                try (InputStream bodyInputStream = send.body()) {
                    String outputFile = topCommand.matchedOptionValue(outputFileOptionSpec.longestName(), (String) null); // No default value.
                    if (outputFile != null) {
                        Files.copy(bodyInputStream, Paths.get(outputFile));
                    } else {
                        bodyInputStream.transferTo(System.out);
                    }
                }
            } catch (IOException | InterruptedException e) {
                System.err.println(e.getMessage());
                return 1;
            }
        }

        return exitCode;
    }

    /**
     * Create URI from the path and method.
     * <p>
     * Insert userInfo to the URI if the authorization is {@link Authorization.UsernameAndPasswordInUriAuthority}.
     *
     * @param pathCommand
     * @param methodCommand
     * @return URI for the request.
     */
    private URI createUri(CommandLine.ParseResult pathCommand, CommandLine.ParseResult methodCommand) {
        Optional<Server> first = openAPI.getServers().stream().findFirst();
        if (first.isEmpty()) {
            throw new RuntimeException("No server specified in OpenAPI spec.");
        }

        String path = pathCommand.commandSpec().name();
        String resolvedPath = resolvePathParameters(path, methodCommand);
        Optional<String> optionalQuery = resolveQueryParameters(path, methodCommand.commandSpec().name(), methodCommand);

        String query = optionalQuery.map((value) -> String.format("?%s", value)).orElse("");

        try {
            String serverUrl = first.get().getUrl() + resolvedPath + query;
            URIBuilder uriBuilder = new URIBuilder(serverUrl);
            if (authorization instanceof Authorization.UsernameAndPasswordInUriAuthority usernameAndPasswordInUriAuthority) {
                uriBuilder.setUserInfo(usernameAndPasswordInUriAuthority.username(), usernameAndPasswordInUriAuthority.password());
            }
            return uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private String resolvePathParameters(String path, CommandLine.ParseResult methodCommand) {
        Pattern pathParamPattern = Pattern.compile("\\{([^}]+)}");
        Matcher pathParamMatcher = pathParamPattern.matcher(path);

        boolean providedAllPathParameters = pathParamMatcher.results().allMatch((matchResult) -> {
            String paramName = matchResult.group(1);
            Optional<String> paramValue = parameterValue(methodCommand, paramName, "path");
            return paramValue.isPresent();
        });

        assert(providedAllPathParameters); // All path parameters are required. so, `providedAllPathParameters` must be true.

        String resolvedPath = pathParamMatcher.replaceAll((matchResult) -> {
            String paramName = matchResult.group(1);
            return parameterValue(methodCommand, paramName, "path").map(Object::toString).get(); // The value is present because `providedAllPathParameters` is true.
        });

        return resolvedPath;
    }

    private <T> Optional<T> parameterValue(CommandLine.ParseResult operationCommand, String parameterName, String location) {
        String optionName = String.format("--%s", parameterName);
        String optionNameWithLocation = String.format("--%s-in-%s", parameterName, location);
        return Optional.ofNullable(operationCommand.matchedOptionValue(optionName, (T) null))
                .or(() -> Optional.ofNullable(operationCommand.matchedOptionValue(optionNameWithLocation, (T) null)));
    }

    /**
     * Return empty list if the list is null.
     * <p>
     *     Some methods of Swagger parser return null instead of empty list.
     * @param list
     * @return
     * @param <T>
     */
    private <T> List<T> toEmptyListIfNull(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private Optional<String> resolveQueryParameters(String path, String method, CommandLine.ParseResult methodCommand) {
        Operation operation = openAPI.getPaths().get(path).readOperationsMap().get(PathItem.HttpMethod.valueOf(method.toUpperCase()));
        List<Parameter> queryParameters = toEmptyListIfNull(operation.getParameters()).stream()
                .filter(parameter -> parameter.getIn().equals("query"))
                .toList();
        String resolvedQuery = queryParameters.stream().flatMap(parameter -> {
                    String parameterName = parameter.getName();
                    return parameterValue(methodCommand, parameterName, "query").map(value -> String.format("%s=%s", parameterName, value)).stream();
                })
                .collect(Collectors.joining("&"));

        return Strings.isNullOrEmpty(resolvedQuery) ? Optional.empty() : Optional.of(resolvedQuery);
    }

    private Multimap<String, String> resolveHeaderParameters(String path, String method, CommandLine.ParseResult methodCommand) {
        Operation operation = openAPI.getPaths().get(path).readOperationsMap().get(PathItem.HttpMethod.valueOf(method.toUpperCase()));
        List<Parameter> headerParameters = toEmptyListIfNull(operation.getParameters()).stream()
                .filter(parameter -> parameter.getIn().equals("header"))
                .toList();
        Multimap<String, String> resolvedHeaders = LinkedListMultimap.create();
        headerParameters.forEach(parameter -> {
            String parameterName = parameter.getName();
            parameterValue(methodCommand, parameterName, "header").map(Object::toString).ifPresent(value -> resolvedHeaders.put(parameterName, value));
        });
        return Multimaps.unmodifiableMultimap(resolvedHeaders);
    }

    private static OpenAPI parseOpenApi(String openApiJsonOrYaml) {
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        SwaggerParseResult swaggerParseResult = new OpenAPIV3Parser().readContents(openApiJsonOrYaml, null, parseOptions);
        OpenAPI openAPI = swaggerParseResult.getOpenAPI();
        log.debug(swaggerParseResult.getMessages().toString());

        return openAPI;
    }

    /**
     * Create top level {@link CommandSpec} object and create nested sub-command & sub-sub-command objects.
     *
     * @param commandName Command name.
     * @param openAPI OpenAPI specification.
     * @return {@link CommandSpec} object.
     */
    private static CommandSpec createCommandSpec(String commandName, OpenAPI openAPI) {
        CommandSpec spec = CommandSpec.create();

        /*
         * Set the name and version of the command.
         * The version is the value of the field "version" in the OpenAPI specification.
         */
        spec.name(commandName);
        spec.version(openAPI.getInfo().getVersion());

        spec.mixinStandardHelpOptions(true);

        spec.usageMessage()
                .description(openAPI.getInfo().getSummary());

        spec.addOption(generateBashAutoCompletionScriptOption);

        spec.addArgGroup(requestBodyArgGroupSpec);
        spec.addOption(outputFileOptionSpec);

        spec.addOption(assertHttpStatusCodeSpec);

        openAPI.getPaths().forEach((path, pathItem) -> {
            CommandSpec pathSpec = pathSpec(path, pathItem);
            methodSpecs(pathItem).forEach(methodSpec -> {
                pathSpec.addSubcommand(methodSpec.name(), methodSpec);
            });

            spec.addSubcommand(path, pathSpec);
        });

        return spec;
    }

    /**
     * Create {@link RestCliSpec} from OpenAPI specification.
     * <p>
     * <strong>This method is heavy.</strong> It may take about 1 second to parse very big API specification.
     * Reuse the returned {@link RestCliSpec} object as much as possible.
     *
     * @param openApiJsonOrYaml OpenAPI specification in JSON or YAML format.
     * @return {@link RestCliSpec} object.
     */
    public static RestCliSpec createRestCliSpec(String commandName, String openApiJsonOrYaml) {
        OpenAPI openApi = parseOpenApi(openApiJsonOrYaml);
        CommandSpec commandSpec = createCommandSpec(commandName, openApi);
        return new RestCliSpec(commandSpec, openApi);
    }

    /**
     * Create CommandSpec object for the path sub-command of picocli.
     *
     * @param path     It is a field pattern of PathItem object of OpenAPI. It includes leading slash '/'.
     * @param pathItem PathItem object of OpenAPI.
     * @return CommandSpec object for the path sub-command of picocli.
     */
    private static CommandSpec pathSpec(String path, PathItem pathItem) {
        CommandSpec pathSpec = CommandSpec.create();
        pathSpec.name(path);
        pathSpec.mixinStandardHelpOptions(true);
        pathSpec.usageMessage()
                .description(pathItem.getSummary());

        return pathSpec;
    }

    private static Set<CommandSpec> methodSpecs(PathItem pathItem) {
        Set<CommandSpec> methodSpecs = new HashSet<>();

        pathItem.readOperationsMap().forEach((method, operation) -> {
            methodSpecs.add(methodSpec(method.toString().toLowerCase(), pathItem, operation));
        });

        return methodSpecs;
    }

    /**
     * @param method    It is one of get, head, post, put, delete, options, trace, patch.
     * @param pathItem  PathItem object of OpenAPI.
     * @param operation Operation object of OpenAPI.
     * @return CommandSpec object for the method sub-sub-command of picocli.
     */
    private static CommandSpec methodSpec(String method, PathItem pathItem, Operation operation) {
        CommandSpec methodSpec = CommandSpec.create();

        methodSpec.mixinStandardHelpOptions(true);

        methodSpec.name(method)
                .usageMessage().description(operation.getSummary());

        // name -> location -> parameter. location is one of "path", "query", "header", "cookie".
        Map<String, Map<String, Parameter>> parameters = parameters(pathItem, operation);
        parameters.forEach((name, locatedParameters) -> {
            // If there are multiple parameters with same name but different location, the option name is like "--name-in-path" and "--name-in-query".
            boolean locationRequired = locatedParameters.size() != 1;

            locatedParameters.forEach((location, parameter) -> {
                String optionName = String.format("--%s", name);
                if (locationRequired) {
                    optionName = String.format("--%s-in-%s", optionName, location);
                }

                OptionSpec.Builder optionSpecBuilder = OptionSpec.builder(optionName)
                        .description(parameter.getDescription())
                        .required(parameter.getRequired() || "path".equals(location));

                Optional<Schema> optionalSchema = Optional.ofNullable(parameter.getSchema());
                optionalSchema.ifPresent(schema -> {
                    Optional<String> optionalType = Optional.ofNullable(schema.getType());
                    optionalType.ifPresent(type -> {
                        Class clazz = switch (type) {
                            case "string" -> String.class;
                            case "integer" -> Integer.class;
                            case "boolean" -> Boolean.class;
                            case "array" -> List.class;
                            default -> throw new IllegalStateException("Unexpected value: " + type);
                        };
                        optionSpecBuilder.type(clazz);
                        optionSpecBuilder.paramLabel(type);
                    });
                });

                methodSpec.addOption(optionSpecBuilder.build());
            });
        });

        return methodSpec;
    }

    private static <T> List<T> emptyListIfNull(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Merge parameters for path-item and operation.
     * Parameter has name and location.
     * Location is one of "path", "query", "header", "cookie". (See <a href="https://spec.openapis.org/oas/v3.1.0#parameterIn">the OpenAPI spec</a>.)
     *
     * @param pathItem
     * @param operation
     * @return Map name -> location -> parameter. For example, {"userId" -> {"path" -> paramUserIdInPath}, "isAdmin" -> {"query" -> paramIsAdminInQuery}}
     */
    private static Map<String, Map<String, Parameter>> parameters(PathItem pathItem, Operation operation) {
        List<Parameter> pathItemParameters = emptyListIfNull(pathItem.getParameters());
        List<Parameter> operationParameters = emptyListIfNull(operation.getParameters());

        Map<String, Map<String, Parameter>> mergedParameters = new HashMap<>();

        pathItemParameters.forEach(parameter -> {
            String name = parameter.getName();
            Map<String, Parameter> locatedParameters = mergedParameters.computeIfAbsent(name, (_name) -> new HashMap<>());
            locatedParameters.put(parameter.getIn(), parameter);
        });

        /*
         * A parameter for operation overrides a parameter for path-item if it has same name and location.
         * See https://spec.openapis.org/oas/v3.1.0#fixed-fields-7
         */
        operationParameters.forEach(overridingParameter -> {
            String name = overridingParameter.getName();
            Map<String, Parameter> locatedParameters = mergedParameters.computeIfAbsent(name, (_name) -> new HashMap<>());
            locatedParameters.put(overridingParameter.getIn(), overridingParameter);
        });

        return mergedParameters;
    }
}
