package org.nopware.librestcli;

import com.google.common.base.Strings;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Slf4j
public class RestCli {
    /**
     * It is just a marker interface for Authorization classes.
     */
    public sealed interface Authorization permits None, Authorization.AuthorizationHeader, Authorization.UsernameAndPasswordInUriAuthority {
        /**
         * No authorization.
         */
        public record None() implements Authorization {
        }

        /**
         * Authorization header in HTTP request header.
         */
        public record AuthorizationHeader(@NonNull String authorizationHeader) implements Authorization {
        }

        /**
         * Username and password in URI authority.
         * <p>
         * example: <pre>{@literal https://username:password@host/path}</pre>
         */
        public record UsernameAndPasswordInUriAuthority(@NonNull String username,
                                                        @NonNull String password) implements Authorization {
        }
    }

    public record RestCliSpec(@NonNull CommandSpec commandSpec, @NonNull OpenAPI openAPI) {
    }

    private final CommandLine commandLine;
    private final OpenAPI openAPI;
    private final Authorization authorization;
    private static final OptionSpec generateBashAutoCompletionScriptOption = OptionSpec.builder("--generate-bash-auto-completion-script")
            .arity("0..1")
            .description("Generate Bash auto completion script and exit.")
            .paramLabel("file")
            .type(String.class)
            .build();

    private RestCli(@NonNull RestCliSpec restCliSpec, @NonNull Authorization authorization) {
        this.commandLine = new CommandLine(restCliSpec.commandSpec);
        this.commandLine.setExecutionStrategy(this::doExecute);
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
                return -1;
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
     * @param restCliSpec  {@link RestCliSpec} object. Created by {@link #createRestCliSpec(String)}.
     * @param authorization Authorization object.
     * @param args        Command line arguments.
     * @return Exit code.
     */
    public static int execute(RestCliSpec restCliSpec, Authorization authorization, String... args) {
        RestCli restCli = new RestCli(restCliSpec, authorization);
        return restCli.execute(args);
    }

    /**
     * Execute the command without authorization.
     * It is same as {@link #execute(RestCliSpec, Authorization, String...)} with {@link RestCli.Authorization.None}.
     *
     * @param restCliSpec {@link RestCliSpec} object. Created by {@link #createRestCliSpec(String)}.
     * @param args       Command line arguments.
     * @return Exit code.
     */
    public static int execute(RestCliSpec restCliSpec, String... args) {
        return execute(restCliSpec, new None(), args);
    }

    private int doExecute(CommandLine.ParseResult parseResult) {
        /*
         * If the help option is specified, the help message is printed and the program exits.
         * The exit code is 0.
         */
        Integer helpExitCode = CommandLine.executeHelpRequest(parseResult);
        if (helpExitCode != null) {
            return helpExitCode;
        }

        /*
         * If the generate bash auto-completion script option is specified, generate the script and the program exits.
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
            return -1;
        }

        /*
         * If multiple paths are specified, print warning message and use the last path.
         */
        if (pathCommands.size() > 1) {
            log.debug("Multiple paths specified. The last path is used.");
        }

        CommandLine.ParseResult pathCommand = pathCommands.get(pathCommands.size() - 1);

        /*
         * If no method is specified, print error message and exit.
         */
        List<CommandLine.ParseResult> methodCommands = pathCommand.subcommands();
        if (methodCommands.isEmpty()) {
            System.err.println("No method specified.");
            return -1;
        }

        /*
         * If multiple operations are specified, print warning message and use the last operation.
         */
        if (methodCommands.size() > 1) {
            log.debug("Multiple operations specified. The last operation is used.");
        }

        CommandLine.ParseResult methodCommand = methodCommands.get(methodCommands.size() - 1);

        return doRestRequest(parseResult, pathCommand, methodCommand);
    }

    private int doRestRequest(CommandLine.ParseResult topCommand, CommandLine.ParseResult pathCommand, CommandLine.ParseResult methodCommand) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2) // Each request will attempt to upgrade to HTTP/2. If the upgrade fails, then the response will be handled using HTTP/1.1
                .proxy(ProxySelector.getDefault()) // Use the system-wide proxy settings.
                .build();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(createUri(pathCommand, methodCommand))
                .method(
                        methodCommand.commandSpec().name().toUpperCase(), // Do not forget to convert to upper case. It is a pitfall about OpenAPI spec.
                        HttpRequest.BodyPublishers.noBody()); // TODO: Support request body.

        Multimap<String, String> resolvedHeaders = resolveHeaderParameters(pathCommand.commandSpec().name(), methodCommand.commandSpec().name(), methodCommand);
        resolvedHeaders.forEach(requestBuilder::header);

        switch (authorization) {
            case Authorization.AuthorizationHeader authorizationHeader ->
                    requestBuilder.header("Authorization", authorizationHeader.authorizationHeader());
            default -> {
            }
        }

        HttpRequest httpRequest = requestBuilder.header("User-Agent", "AweSome-App")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build();

        log.info("Request: {}", httpRequest.toString());
        log.info("URL: {}", httpRequest.uri());
        log.info("Method: {}", httpRequest.method());
        log.info("Headers: {}", httpRequest.headers().map().toString());

        try {
            HttpResponse<String> send = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("Response code: {}", send.statusCode());
            log.info("ResponseHeaders: {}", send.headers().map().toString());
            System.out.println(send.body());
        } catch (IOException | InterruptedException e) {
            System.err.println(e.getMessage());
            return -1;
        }

        return 0;
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

        List<String> notProvidedPathParameters = new LinkedList<>();

        boolean providedAllPathParameters = pathParamMatcher.results().allMatch((matchResult) -> {
            String paramName = matchResult.group(1);
            Optional<String> paramValue = parameterValue(methodCommand, paramName, "path");
            if (paramValue.isEmpty()) {
                notProvidedPathParameters.add(paramName);
            }
            return paramValue.isPresent();
        });

        if (!providedAllPathParameters) {
            throw new RuntimeException(String.format("Not provided path parameters: %s", notProvidedPathParameters));
        }

        String resolvedPath = pathParamMatcher.replaceAll((matchResult) -> {
            String paramName = matchResult.group(1);
            return parameterValue(methodCommand, paramName, "path").get(); // The value is present because `providedAllPathParameters` is true.
        });

        return resolvedPath;
    }

    private Optional<String> parameterValue(CommandLine.ParseResult operationCommand, String parameterName, String location) {
        String optionName = String.format("--%s", parameterName);
        String optionNameWithLocation = String.format("--%s-in-%s", parameterName, location);
        return Optional.ofNullable(operationCommand.matchedOptionValue(optionName, (String) null))
                .or(() -> Optional.ofNullable(operationCommand.matchedOptionValue(optionNameWithLocation, (String) null)));
    }

    private Optional<String> resolveQueryParameters(String path, String method, CommandLine.ParseResult methodCommand) {
        Operation operation = openAPI.getPaths().get(path).readOperationsMap().get(PathItem.HttpMethod.valueOf(method.toUpperCase()));
        List<Parameter> queryParameters = operation.getParameters().stream()
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
        List<Parameter> headerParameters = operation.getParameters().stream()
                .filter(parameter -> parameter.getIn().equals("header"))
                .toList();
        Multimap<String, String> resolvedHeaders = LinkedListMultimap.create();
        headerParameters.forEach(parameter -> {
            String parameterName = parameter.getName();
            parameterValue(methodCommand, parameterName, "header").ifPresent(value -> resolvedHeaders.put(parameterName, value));
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
     * @param openAPI OpenAPI specification.
     * @return {@link CommandSpec} object.
     */
    private static CommandSpec createCommandSpec(OpenAPI openAPI) {
        CommandSpec spec = CommandSpec.create();
        Properties properties = loadProperties();

        /*
         * Set the name and version of the command.
         * The name is the value of the property "commandName" in librestcli.properties. It is defined in pom.xml.
         * The version is the value of the field "version" in the OpenAPI specification.
         */
        spec.name(properties.getProperty("commandName"));
        spec.version(openAPI.getInfo().getVersion());

        spec.mixinStandardHelpOptions(true);

        spec.usageMessage()
                .description(openAPI.getInfo().getSummary());

        spec.addOption(generateBashAutoCompletionScriptOption);

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
     * This method is heavy. It may take about 1 second to parse very big API specification.
     * Reuse the returned {@link RestCliSpec} object as much as possible.
     *
     * @param openApiJsonOrYaml OpenAPI specification in JSON or YAML format.
     * @return {@link RestCliSpec} object.
     */
    public static RestCliSpec createRestCliSpec(String openApiJsonOrYaml) {
        OpenAPI openApi = parseOpenApi(openApiJsonOrYaml);
        CommandSpec commandSpec = createCommandSpec(openApi);
        return new RestCliSpec(commandSpec, openApi);
    }

    /**
     * Load librestcli.properties that is generated by Maven from pom.xml.
     * @return
     */
    private static Properties loadProperties() {
        URL librestcliPropertiesUrl = Resources.getResource("librestcli.properties");
        CharSource charSource = Resources.asCharSource(librestcliPropertiesUrl, Charset.defaultCharset());
        try {
            Properties properties = new Properties();
            properties.load(charSource.openStream());
            return properties;
        } catch (IOException never) {
            // The property file is generated by Maven automatically and is always available.
            throw new RuntimeException(never);
        }
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

        Operation get = pathItem.getGet();
        if (get != null) {
            methodSpecs.add(methodSpec("get", pathItem, get));
        }

        Operation head = pathItem.getHead();
        if (head != null) {
            methodSpecs.add(methodSpec("head", pathItem, head));
        }

        Operation post = pathItem.getPost();
        if (post != null) {
            methodSpecs.add(methodSpec("post", pathItem, post));
        }

        Operation put = pathItem.getPut();
        if (put != null) {
            methodSpecs.add(methodSpec("put", pathItem, put));
        }

        Operation delete = pathItem.getDelete();
        if (delete != null) {
            methodSpecs.add(methodSpec("delete", pathItem, delete));
        }

        Operation options = pathItem.getOptions();
        if (options != null) {
            methodSpecs.add(methodSpec("options", pathItem, options));
        }

        Operation trace = pathItem.getTrace();
        if (trace != null) {
            methodSpecs.add(methodSpec("trace", pathItem, trace));
        }

        Operation patch = pathItem.getPatch();
        if (patch != null) {
            methodSpecs.add(methodSpec("patch", pathItem, patch));
        }

        return methodSpecs;
    }

    /**
     *
     * @param method It is one of get, head, post, put, delete, options, trace, patch.
     * @param pathItem PathItem object of OpenAPI.
     * @param operation Operation object of OpenAPI.
     * @return CommandSpec object for the method sub-sub-command of picocli.
     */
    private static CommandSpec methodSpec(String method, PathItem pathItem, Operation operation) {
        CommandSpec methodSpec = CommandSpec.create();

        methodSpec.mixinStandardHelpOptions(true);

        methodSpec.name(method)
                .usageMessage().description(operation.getSummary());

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
                        .required(parameter.getRequired());

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
