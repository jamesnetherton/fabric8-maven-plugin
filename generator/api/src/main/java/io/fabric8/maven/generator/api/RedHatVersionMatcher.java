package io.fabric8.maven.generator.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

public final class RedHatVersionMatcher {

    private static final String VERSION_SUFFIX_PROPERTIES = "/META-INF/fabric8/redhat-version-suffix.properties";
    private static final String DEFAULT_SUFFIX = "redhat";
    private Pattern redHatVersionPattern;

    public RedHatVersionMatcher() {
        try {
            String versionSuffix;
            InputStream resource = RedHatVersionMatcher.class.getResourceAsStream(VERSION_SUFFIX_PROPERTIES);

            if (resource != null) {
                Properties properties = new Properties();
                properties.load(resource);
                versionSuffix = properties.getProperty("redhat.version.suffix", DEFAULT_SUFFIX);
            } else {
                versionSuffix = DEFAULT_SUFFIX;
            }

            redHatVersionPattern = Pattern.compile("^.*\\." + versionSuffix + "-.*$");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load version suffix from " + VERSION_SUFFIX_PROPERTIES, e);
        }
    }

    public boolean matches(String version) {
        return redHatVersionPattern.matcher(version).matches();
    }
}
