## 1. Does Jex support SSL during testing?

Yes. Since our WebRouterConfiguration factory initializes Jex by reading Config.getBool (
"server.ssl.enabled"), if we set server.ssl.enabled=true inside application-test.properties and
provide a test keystore path, Jex will happily boot over an HTTPS socket on its randomly assigned
port 0.

## 2. What blocks us if we turn it on?

(The Overhead) If we activate SSL inside WebhookControllerComponentTest, our test HTTP client
(avaje-http-client or Java's native HttpClient) will immediately start failing with a
SSLHandshakeException.Because our local development certificate is Self-Signed (generated via
keytool), the JVM's default TrustStore does not recognize it as a trusted certificate authority
(like Let's Encrypt or DigiCert). To make the test pass, we would be forced to write significant
infrastructure boilerplate inside our @BeforeAll block to:Initialize a custom Java TrustManager that
blindly trusts all certificates.Build an SSLContext that bypasses hostname verification.Inject that
insecure socket factory into our test HttpClient.This adds brittle, non-functional code to our test
suite just to appease the network handshaking layer.

## 3. The Clean Architecture Perspective:

**What are we actually testing?**
In Clean Architecture, we use Separation of Concerns to isolate our testing targets:

* **The Web Module (salon-web-http)** is responsible for verifying Application Layer routing and
  syntax constraints. We want to test if our JSON deserializes into IncomingMessageDto, if @Valid
  annotation validation screens block empty parameters, and if the route returns 204 No Content. The
  encryption of the TCP socket packets has zero effect on this logic.
* **The Security Layer (SSL/TLS)** is an Infrastructure concern. Testing whether Jetty can decrypt
  TLS 1.3 handshakes or read a .p12 keystore file is something we test at the deployment boundary
  level (e.g., during full end-to-end system smoke tests or through reverse proxies), not inside an
  isolated controller component test.

## The Recommendation: Follow the ai-demo Rule of PragmatismKeep

server.ssl.enabled=false inside salon-web-http/src/test/resources/application-test.properties.This
keeps your component tests lightning-fast, completely stable, and clear of SSL handshake
boilerplates, while allowing you to fully verify your REST mapping logic. We leave the strict SSL
runtime enforcement to our salon-boot production configurations and real environment deployment
checks.