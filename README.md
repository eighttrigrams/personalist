# Personalist

```
clojure -M -m uberdeps.uberjar --target go.jar && java --add-opens=java.base/java.nio=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true -cp go.jar clojure.main -m go
```

yields

```
-       skipping duplicate lib org.apache.maven.resolver/maven-resolver-spi #:mvn{:version 1.8.2}
-       skipping duplicate lib org.apache.maven.resolver/maven-resolver-util #:mvn{:version 1.8.2}
.       org.slf4j/jcl-over-slf4j #:mvn{:version "1.7.36"}
-     skipping duplicate lib org.apache.maven.resolver/maven-resolver-util #:mvn{:version 1.8.2}
-     skipping duplicate lib org.clojure/data.xml #:mvn{:version 0.2.0-alpha8}
.     org.clojure/tools.cli #:mvn{:version "1.0.214"}
.     org.clojure/tools.gitlibs #:mvn{:version "2.5.190"}
[uberdeps] Packaged go.jar in 12717 ms
Syntax error reading source at (xtdb/types.clj:777:47).
No reader function for tag xt/date
```