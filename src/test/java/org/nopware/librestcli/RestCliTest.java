package org.nopware.librestcli;

import com.google.common.io.Resources;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class RestCliTest {
    /*
     * Let's use the GitHub API as an example.
     */
    private static final RestCli GITHUB_API;

    static {
        try {
            long begin = System.currentTimeMillis();
            GITHUB_API = RestCli.parseOpenApi(
                    Resources.toString(
                            Resources.getResource("api.github.com.json"), Charset.defaultCharset()));
            long end = System.currentTimeMillis();
            log.debug("Parsing the GitHub API took {} ms.", end - begin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void test() throws IOException {
        GITHUB_API.execute("/repos/{owner}/{repo}/issues", "get", "--owner=naoshi-higuchi", "--repo=flist");
    }

    @Test
    public void testGenerateBashAutoCompletionScript() throws IOException {
        int exit = GITHUB_API.execute("--generate-bash-auto-completion-script");
        assertThat(exit).isZero();
    }

    @Test
    public void testGenerateBashAutoCompletionScriptToFile(@TempDir Path tempDir) throws IOException {
        Path bashAutoCompletionScript = tempDir.resolve("bash-autocompletion.sh");

        int exit = GITHUB_API.execute("--generate-bash-auto-completion-script", bashAutoCompletionScript.toString());
        assertThat(exit).isZero();

        assertThat(bashAutoCompletionScript).exists();
    }

    @Test
    public void testVersion() throws IOException {
        int exit = GITHUB_API.execute("--version");
        assertThat(exit).isZero();
    }

    @Test
    public void testHelp() throws IOException {
        int exit = GITHUB_API.execute("--help");
        assertThat(exit).isZero();
    }

    @Test
    public void testHelpForPath() throws IOException {
        int exit = GITHUB_API.execute("/users", "--help");
        assertThat(exit).isZero();
    }

    @Test
    public void testHelpForPathAndMethod() throws IOException {
        int exit = GITHUB_API.execute("/users", "get", "--help");
        assertThat(exit).isZero();
    }
}
