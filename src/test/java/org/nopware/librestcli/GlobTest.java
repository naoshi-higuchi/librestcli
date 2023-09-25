package org.nopware.librestcli;

import org.junit.jupiter.api.Test;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

public class GlobTest {
    @Test
    public void testGlobMatcherForOpenApiPath() {
        Path path = Paths.get("/repos/{owner}/{repo}/issues"); // OpenAPI path, including path parameters.

        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:/repos/**");
        assertThat(pathMatcher.matches(path)).isTrue();

        PathMatcher pathMatcher2 = FileSystems.getDefault().getPathMatcher("glob:/repos/*/*/issues");
        assertThat(pathMatcher2.matches(path)).isTrue();

        PathMatcher pathMatcher3 = FileSystems.getDefault().getPathMatcher("glob:/repos/*/issues");
        assertThat(pathMatcher3.matches(path)).isFalse();
    }
}
