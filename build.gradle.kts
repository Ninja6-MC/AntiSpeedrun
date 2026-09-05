plugins {
    `java-library`
}

group = "com.ninja6"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.kyori:adventure-api:4.18.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.18.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // paper-api is compileOnly, so Bukkit's YamlConfiguration is not on the test classpath.
    // PluginConfig therefore parses from com.ninja6.antispeedrun.config.ConfigSection rather than
    // from FileConfiguration, and the tests drive that seam with real YAML through SnakeYAML --
    // the same parser Bukkit itself uses. Test scope only: nothing in src/main imports it.
    testImplementation("org.yaml:snakeyaml:2.2")

    // Test scope only, and only so ConfigSectionConformanceTest can run one set of assertions
    // against BukkitConfigSection as well as MapConfigSection. org.bukkit.configuration is plain
    // library code in the API jar -- YamlConfiguration.loadFromString needs no running server --
    // so the adapter that production actually uses is exercised rather than assumed equivalent.
    // src/main still compiles against paper-api as compileOnly; nothing here changes that.
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    test {
        useJUnitPlatform()
    }
}
