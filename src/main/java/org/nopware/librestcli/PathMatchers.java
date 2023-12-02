package org.nopware.librestcli;

import lombok.NonNull;

import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.regex.Pattern;

class PathMatchers {

    /**
     * PathMatcher matches all paths.
     */
    static final class AllMatcher implements RestCli.PathMatcher {
        @Override
        public boolean matches(@NonNull String path) {
            return true; // Always true.
        }
    }

    /**
     * PathMatcher matches the specified path.
     */
    static final class StringMatcher implements RestCli.PathMatcher {
        private final String path;

        StringMatcher(@NonNull String path) {
            this.path = path;
        }

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
    static final class GlobMatcher implements RestCli.PathMatcher {
        private final java.nio.file.PathMatcher pathMatcher;

        GlobMatcher(String globPattern) {
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
    static final class RegexMatcher implements RestCli.PathMatcher {
        private final Pattern pattern;

        RegexMatcher(String regex) {
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
    static final class MatcherWithException implements RestCli.PathMatcher {
        private final RestCli.PathMatcher pathMatcher;
        private final RestCli.PathMatcher exceptionPathMatcher;

        MatcherWithException(@NonNull RestCli.PathMatcher pathMatcher, @NonNull RestCli.PathMatcher exceptionPathMatcher) {
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
    static final class CustomMatcher implements RestCli.PathMatcher {
        private final Predicate<String> pathPredicate;

        CustomMatcher(@NonNull Predicate<String> pathPredicate) {
            this.pathPredicate = pathPredicate;
        }

        @Override
        public boolean matches(@NonNull String path) {
            return this.pathPredicate.test(path);
        }
    }
}
