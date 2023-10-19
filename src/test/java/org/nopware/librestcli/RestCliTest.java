package org.nopware.librestcli;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class RestCliTest {
    /*
     * Let's use the GitHub API as an example.
     */
    private static final String GITHUB_API_SPEC;
    private static final RestCli.RestCliSpec REST_CLI_SPEC;

    static {
        try {
            long begin = System.currentTimeMillis();
            GITHUB_API_SPEC = Resources.toString(
                    Resources.getResource("api.github.com.json"), Charset.defaultCharset());
            REST_CLI_SPEC = RestCli.createRestCliSpec("restcli", GITHUB_API_SPEC);
            long end = System.currentTimeMillis();
            log.debug("Parsing the GitHub API took {} ms.", end - begin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void test() throws IOException {
        int get = RestCli.execute(REST_CLI_SPEC, "/repos/{owner}/{repo}/issues", "get", "--owner=naoshi-higuchi", "--repo=flist");
        assertThat(get).isZero();
    }

    @Test
    public void testGenerateBashAutoCompletionScript() {
        int exit = RestCli.execute(REST_CLI_SPEC, "--generate-bash-auto-completion-script");
        assertThat(exit).isZero();
    }

    @Test
    public void testGenerateBashAutoCompletionScriptToFile(@TempDir Path tempDir) {
        Path bashAutoCompletionScript = tempDir.resolve("bash-autocompletion.sh");

        int exit = RestCli.execute(REST_CLI_SPEC, "--generate-bash-auto-completion-script", bashAutoCompletionScript.toString());
        assertThat(exit).isZero();

        assertThat(bashAutoCompletionScript).exists();
    }

    @Test
    public void testVersion() {
        int exit = RestCli.execute(REST_CLI_SPEC, "--version");
        assertThat(exit).isZero();
    }

    @Test
    public void testHelp() {
        int exit = RestCli.execute(REST_CLI_SPEC, "--help");
        assertThat(exit).isZero();
    }

    @Test
    public void testHelpForPath() {
        int exit = RestCli.execute(REST_CLI_SPEC, "/users", "--help");
        assertThat(exit).isZero();
    }

    @Test
    public void testHelpForPathAndMethod() {
        int exit = RestCli.execute(REST_CLI_SPEC, "/users", "get", "--help");
        assertThat(exit).isZero();
    }

    @Test
    public void testAuthorizationHeader() {
        String authorizationHeaderString = System.getenv("TEST_RESTCLI_GITHUB_API_TOKEN");
        if (authorizationHeaderString == null) {
            log.warn("Environment variable TEST_RESTCLI_GITHUB_API_TOKEN is not set. Skipping the test.");
            log.warn("example: $ export TEST_RESTCLI_GITHUB_API_TOKEN=\"token ghp_0123456789abcdef0123456789abcdef01234567\"");
            return;
        }

        RestCli.Authorization authorizationHeader = new RestCli.Authorization.AuthorizationHeader(authorizationHeaderString);
        RestCli.execute(REST_CLI_SPEC, authorizationHeader, "/repos/{owner}/{repo}/issues", "get", "--owner=naoshi-higuchi", "--repo=flist");
    }

    /**
     * Measure the time to create a {@link CommandSpec} from an OpenAPI specification.
     */
    @Test
    public void measureCreateCommandSpecTime() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = RestCli.class.getDeclaredMethod("createCommandSpec", String.class, OpenAPI.class);
        method.setAccessible(true);

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        SwaggerParseResult swaggerParseResult = new OpenAPIV3Parser().readContents(GITHUB_API_SPEC, null, parseOptions);
        OpenAPI openAPI = swaggerParseResult.getOpenAPI();

        long begin = System.currentTimeMillis();
        CommandSpec commandSpec = (CommandSpec) method.invoke(RestCli.class, "librestcli", openAPI);
        long end = System.currentTimeMillis();

        System.out.println(commandSpec.name());

        System.out.printf("Creating CommandSpec took %d ms.%n", end - begin);
    }

    /**
     * Measure the time to construct a {@link CommandLine} from a {@link CommandSpec}.
     * This test should be in PicoCliSpecTest.java, but it is here because it is related to how command-spec is constructed by RestCli.
     */
    @Test
    public void measureConstructCommandLineTime() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = RestCli.class.getDeclaredMethod("createCommandSpec", String.class, OpenAPI.class);
        method.setAccessible(true);

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        SwaggerParseResult swaggerParseResult = new OpenAPIV3Parser().readContents(GITHUB_API_SPEC, null, parseOptions);
        OpenAPI openAPI = swaggerParseResult.getOpenAPI();
        CommandSpec commandSpec = (CommandSpec) method.invoke(RestCli.class, "librestcli", openAPI);

        long begin = System.currentTimeMillis();
        CommandLine commandLine = new CommandLine(commandSpec);
        long end = System.currentTimeMillis();

        commandLine.printVersionHelp(System.out);

        System.out.printf("Constructing CommandLine took %d ms.%n", end - begin);
    }

    /**
     * Test if the command fails when a required path parameter is missing.
     * <p>
     * All path parameters are required.
     */
    @Test
    public void testMissingPathParameter() {
        // path parameter {owner} and {repo} are required, but {repo} is missing.
        int exit = RestCli.execute(REST_CLI_SPEC, "/repos/{owner}/{repo}/issues", "get", "--owner=naoshi-higuchi");
        assertThat(exit).isNotZero();
    }

    @Test
    public void testPathMatcher() {
        assertThat(RestCli.PathMatcher.all().matches("/foo")).isTrue();

        assertThat(RestCli.PathMatcher.string("/foo").matches("/foo")).isTrue();
        assertThat(RestCli.PathMatcher.string("/foo").matches("/bar")).isFalse();

        assertThat(RestCli.PathMatcher.glob("/repos/**").matches("/repos/{owner}/{repo}/issues")).isTrue();
        assertThat(RestCli.PathMatcher.glob("/repos/**").matches("/repos")).isFalse();
        assertThat(RestCli.PathMatcher.glob("/repos/*/*/issues").matches("/repos/{owner}/{repo}/issues")).isTrue();
        assertThat(RestCli.PathMatcher.glob("/repos/*/issues").matches("/repos/{owner}/{repo}/issues")).isFalse();

        assertThat(RestCli.PathMatcher.regex("/repos/[^/]+/[^/]+/issues").matches("/repos/{owner}/{repo}/issues")).isTrue();

        assertThat(RestCli.PathMatcher.custom(path -> path.startsWith("/repos/")).matches("/repos/{owner}/{repo}/issues")).isTrue();
        assertThat(RestCli.PathMatcher.custom(path -> path.length() == 42).matches("/repos/{owner}/{repo}/issues")).isFalse();

        assertThat(RestCli.PathMatcher.all().except(RestCli.PathMatcher.string("/login")).matches("/login")).isFalse();
    }

    @Test
    public void testOptionAppender() {
        RestCli.OptionAppender optionAppender = new RestCli.OptionAppender(
                RestCli.PathMatcher.glob("/foo/*/*/bar"),
                Sets.newHashSet("put", "post", "delete"),
                (options) -> {
                    options.add("--X-API-KEY=secret");
                    options.add("--force");
                });

        Optional<List<String>> options = optionAppender.getOptions("/foo/one/two/bar", "put");
        assertThat(options.isPresent()).isTrue();
        assertThat(options.get()).isEqualTo(Lists.newArrayList("--X-API-KEY=secret", "--force"));

        Optional<List<String>> optionsPathUnmatched = optionAppender.getOptions("/foo/one/two/baz", "put");
        assertThat(optionsPathUnmatched.isPresent()).isFalse();

        Optional<List<String>> optionsOperationUnmatched = optionAppender.getOptions("/foo/one/two/bar", "get");
        assertThat(optionsOperationUnmatched.isPresent()).isFalse();
    }

    @Test
    public void testOptionAppenderCanReturnEmptyList() {
        RestCli.OptionAppender optionAppender = new RestCli.OptionAppender(
                RestCli.PathMatcher.glob("/foo/*/*/bar"),
                Sets.newHashSet("put", "post", "delete"),
                (options) -> {
                    // Do nothing.
                });

        Optional<List<String>> options = optionAppender.getOptions("/foo/one/two/bar", "put");
        assertThat(options.isPresent()).isTrue();
        assertThat(options.get()).isEqualTo(Collections.emptyList());
    }

    @Test
    public void testFileOutputOption(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("output");
        int get = RestCli.execute(REST_CLI_SPEC, "--output-file", outputFile.toString(), "/repos/{owner}/{repo}/issues", "get", "--owner=naoshi-higuchi", "--repo=flist");
        assertThat(get).isZero();
        assertThat(outputFile).exists();
        assertThat(outputFile).isNotEmptyFile(); // I should check the content, but it is not definite.
    }
}
