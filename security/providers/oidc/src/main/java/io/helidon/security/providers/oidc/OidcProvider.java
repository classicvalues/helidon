/*
 * Copyright (c) 2018, 2021 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.security.providers.oidc;

import java.lang.annotation.Annotation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.helidon.common.Errors;
import io.helidon.common.http.FormParams;
import io.helidon.common.http.Http;
import io.helidon.common.http.MediaType;
import io.helidon.common.reactive.Single;
import io.helidon.config.Config;
import io.helidon.config.DeprecatedConfig;
import io.helidon.config.metadata.Configured;
import io.helidon.config.metadata.ConfiguredOption;
import io.helidon.security.AuthenticationResponse;
import io.helidon.security.EndpointConfig;
import io.helidon.security.Grant;
import io.helidon.security.OutboundSecurityResponse;
import io.helidon.security.Principal;
import io.helidon.security.ProviderRequest;
import io.helidon.security.Role;
import io.helidon.security.Security;
import io.helidon.security.SecurityEnvironment;
import io.helidon.security.SecurityLevel;
import io.helidon.security.SecurityResponse;
import io.helidon.security.Subject;
import io.helidon.security.abac.scope.ScopeValidator;
import io.helidon.security.jwt.Jwt;
import io.helidon.security.jwt.JwtException;
import io.helidon.security.jwt.JwtUtil;
import io.helidon.security.jwt.SignedJwt;
import io.helidon.security.jwt.jwk.JwkKeys;
import io.helidon.security.providers.common.OutboundConfig;
import io.helidon.security.providers.common.OutboundTarget;
import io.helidon.security.providers.common.TokenCredential;
import io.helidon.security.providers.oidc.common.OidcConfig;
import io.helidon.security.providers.oidc.common.OidcCookieHandler;
import io.helidon.security.spi.AuthenticationProvider;
import io.helidon.security.spi.OutboundSecurityProvider;
import io.helidon.security.spi.SecurityProvider;
import io.helidon.security.util.TokenHandler;
import io.helidon.webclient.WebClientRequestBuilder;

import static io.helidon.security.providers.oidc.common.OidcConfig.postJsonResponse;

/**
 * Open ID Connect authentication provider.
 *
 * IDCS specific notes:
 * <ul>
 * <li>If you want to use JWK to validate tokens, you must give access to the endpoint (by default only admin can access it)</li>
 * <li>If you want to use introspect endpoint to validate tokens, you must give rights to the application to do so (Client
 * Configuration/Allowed Operations)</li>
 * <li>If you want to retrieve groups when using IDCS, you must add "Client Credentials" in "Allowed Grant Types" in
 * application configuration, as well as "Grant the client access to Identity Cloud Service Admin APIs." configured to "User
 * Administrator"</li>
 * </ul>
 */
public final class OidcProvider implements AuthenticationProvider, OutboundSecurityProvider {
    private static final Logger LOGGER = Logger.getLogger(OidcProvider.class.getName());

    private final boolean optional;
    private final OidcConfig oidcConfig;
    private final TokenHandler paramHeaderHandler;

    private final BiFunction<SignedJwt, Errors.Collector, Single<Errors.Collector>> jwtValidator;
    private final Pattern attemptPattern;
    private final boolean propagate;
    private final OidcOutboundConfig outboundConfig;
    private final boolean useJwtGroups;
    private final BiConsumer<StringBuilder, String> scopeAppender;
    private final OidcCookieHandler cookieHandler;

    private OidcProvider(Builder builder, OidcOutboundConfig oidcOutboundConfig) {
        this.optional = builder.optional;
        this.oidcConfig = builder.oidcConfig;
        this.propagate = builder.propagate && (oidcOutboundConfig.hasOutbound());
        this.useJwtGroups = builder.useJwtGroups;
        this.outboundConfig = oidcOutboundConfig;
        this.cookieHandler = oidcConfig.tokenCookieHandler();

        attemptPattern = Pattern.compile(".*?" + oidcConfig.redirectAttemptParam() + "=(\\d+).*");

        // must re-configure integration with webserver and jersey

        if (oidcConfig.useParam()) {
            paramHeaderHandler = TokenHandler.forHeader(OidcConfig.PARAM_HEADER_NAME);
        } else {
            paramHeaderHandler = null;
        }

        // clean the scope audience - must end with / if exists
        String configuredScopeAudience = oidcConfig.scopeAudience();
        if (null == configuredScopeAudience || configuredScopeAudience.isEmpty()) {
            this.scopeAppender = StringBuilder::append;
        } else {
            if (configuredScopeAudience.endsWith("/")) {
                this.scopeAppender = (stringBuilder, scope) -> stringBuilder.append(configuredScopeAudience).append(scope);
            } else {
                this.scopeAppender = (stringBuilder, scope) -> stringBuilder.append(configuredScopeAudience)
                        .append("/")
                        .append(scope);
            }
        }

        if (oidcConfig.validateJwtWithJwk()) {
            this.jwtValidator = (signedJwt, collector) -> {
                JwkKeys jwk = oidcConfig.signJwk();
                Errors errors = signedJwt.verifySignature(jwk);
                errors.forEach(errorMessage -> {
                    switch (errorMessage.getSeverity()) {
                    case FATAL:
                        collector.fatal(errorMessage.getSource(), errorMessage.getMessage());
                        break;
                    case WARN:
                        collector.warn(errorMessage.getSource(), errorMessage.getMessage());
                        break;
                    case HINT:
                    default:
                        collector.hint(errorMessage.getSource(), errorMessage.getMessage());
                        break;
                    }
                });
                return Single.just(collector);
            };
        } else {
            this.jwtValidator = (signedJwt, collector) -> {
                FormParams.Builder form = FormParams.builder()
                        .add("token", signedJwt.tokenContent());

                WebClientRequestBuilder post = oidcConfig.appWebClient()
                        .post()
                        .uri(oidcConfig.introspectUri())
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(it -> {
                            it.add(Http.Header.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
                            return it;
                        });

                oidcConfig.updateRequest(OidcConfig.RequestType.INTROSPECT_JWT, post, form);

                return postJsonResponse(post,
                                        form.build(),
                                        json -> {
                                            if (!json.getBoolean("active")) {
                                                collector.fatal(json, "Token is not active");
                                            }
                                            return collector;
                                        },
                                        (status, message) ->
                                                Optional.of(collector.fatal(status,
                                                                            "Failed to validate token, response "
                                                                                    + "status: "
                                                                                    + status
                                                                                    + ", entity: " + message)),
                                        (t, message) ->
                                                Optional.of(collector.fatal(t,
                                                                            "Failed to validate token, request failed: "
                                                                                    + message)));
            };
        }
    }

    /**
     * Load this provider from configuration.
     *
     * @param config configuration of this provider
     * @return a new provider configured for OIDC
     */
    public static OidcProvider create(Config config) {
        return builder().config(config).build();
    }

    /**
     * Create a new provider based on OIDC configuration.
     *
     * @param config config of OIDC server and client
     * @return a new provider configured for OIDC
     */
    public static OidcProvider create(OidcConfig config) {
        return builder().oidcConfig(config).build();
    }

    /**
     * A fluent API builder to created instances of this provider.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Collection<Class<? extends Annotation>> supportedAnnotations() {
        return Set.of(ScopeValidator.Scope.class, ScopeValidator.Scopes.class);
    }

    @Override
    public CompletionStage<AuthenticationResponse> authenticate(ProviderRequest providerRequest) {
        /*
        1. Get token from request - if available, validate it and continue
        2. If not - Redirect to login page
         */
        List<String> missingLocations = new LinkedList<>();

        Optional<String> token = Optional.empty();

        try {
            if (oidcConfig.useHeader()) {
                token = token
                        .or(() -> oidcConfig.headerHandler().extractToken(providerRequest.env().headers()));

                if (token.isEmpty()) {
                    missingLocations.add("header");
                }
            }

            if (oidcConfig.useParam()) {
                token = token
                        .or(() -> paramHeaderHandler.extractToken(providerRequest.env().headers()));

                if (token.isEmpty()) {
                    missingLocations.add("query-param");
                }
            }

            if (oidcConfig.useCookie()) {
                if (token.isEmpty()) {
                    // only do this for cookies
                    Optional<Single<String>> cookie = cookieHandler.findCookie(providerRequest.env().headers());
                    if (cookie.isEmpty()) {
                        missingLocations.add("cookie");
                    } else {
                        return cookie.get()
                                .flatMapSingle(it -> validateToken(providerRequest, it))
                                .onErrorResumeWithSingle(throwable -> {
                                    if (LOGGER.isLoggable(Level.FINEST)) {
                                        LOGGER.log(Level.FINEST, "Invalid token in cookie", throwable);
                                    }
                                    return Single.just(errorResponse(providerRequest,
                                                                     Http.Status.UNAUTHORIZED_401,
                                                                     null,
                                                                     "Invalid token"));
                                });
                    }
                }
            }
        } catch (SecurityException e) {
            LOGGER.log(Level.FINEST, "Failed to extract token from one of the configured locations", e);
            return failOrAbstain("Failed to extract one of the configured tokens" + e);
        }

        if (token.isPresent()) {
            return validateToken(providerRequest, token.get());
        } else {
            LOGGER.finest(() -> "Missing token, could not find in either of: " + missingLocations);
            return CompletableFuture.completedFuture(errorResponse(providerRequest,
                                                                   Http.Status.UNAUTHORIZED_401,
                                                                   null,
                                                                   "Missing token, could not find in either of: "
                                                                           + missingLocations));
        }
    }

    private Set<String> expectedScopes(ProviderRequest request) {

        Set<String> result = new HashSet<>();

        for (SecurityLevel securityLevel : request.endpointConfig().securityLevels()) {
            List<ScopeValidator.Scopes> expectedScopes = securityLevel.combineAnnotations(ScopeValidator.Scopes.class,
                                                                                          EndpointConfig.AnnotationScope
                                                                                                  .values());
            expectedScopes.stream()
                    .map(ScopeValidator.Scopes::value)
                    .map(Arrays::asList)
                    .map(List::stream)
                    .forEach(stream -> stream.map(ScopeValidator.Scope::value)
                            .forEach(result::add));

            List<ScopeValidator.Scope> expectedScopeAnnotations = securityLevel.combineAnnotations(ScopeValidator.Scope.class,
                                                                                                   EndpointConfig.AnnotationScope
                                                                                                           .values());

            expectedScopeAnnotations.stream()
                    .map(ScopeValidator.Scope::value)
                    .forEach(result::add);
        }

        return result;
    }

    private AuthenticationResponse errorResponse(ProviderRequest providerRequest,
                                                 Http.Status status,
                                                 String code,
                                                 String description) {
        if (oidcConfig.shouldRedirect()) {
            // make sure we do not exceed redirect limit
            String state = origUri(providerRequest);
            int redirectAttempt = redirectAttempt(state);
            if (redirectAttempt >= oidcConfig.maxRedirects()) {
                return errorResponseNoRedirect(code, description, status);
            }

            Set<String> expectedScopes = expectedScopes(providerRequest);

            StringBuilder scopes = new StringBuilder(oidcConfig.baseScopes());

            for (String expectedScope : expectedScopes) {
                if (scopes.length() > 0) {
                    // space after base scopes
                    scopes.append(' ');
                }
                String scope = expectedScope;
                if (scope.startsWith("/")) {
                    scope = scope.substring(1);
                }
                scopeAppender.accept(scopes, scope);
            }

            String scopeString;
            scopeString = URLEncoder.encode(scopes.toString(), StandardCharsets.UTF_8);

            String authorizationEndpoint = oidcConfig.authorizationEndpointUri();
            String nonce = UUID.randomUUID().toString();
            String redirectUri = redirectUri(providerRequest.env());


            StringBuilder queryString = new StringBuilder("?");
            queryString.append("client_id=").append(oidcConfig.clientId()).append("&");
            queryString.append("response_type=code&");
            queryString.append("redirect_uri=").append(redirectUri).append("&");
            queryString.append("scope=").append(scopeString).append("&");
            queryString.append("nonce=").append(nonce).append("&");
            queryString.append("state=").append(encodeState(state));

            // must redirect
            return AuthenticationResponse
                    .builder()
                    .status(SecurityResponse.SecurityStatus.FAILURE_FINISH)
                    .statusCode(Http.Status.TEMPORARY_REDIRECT_307.code())
                    .description("Redirecting to identity server: " + description)
                    .responseHeader("Location", authorizationEndpoint + queryString)
                    .build();
        } else {
            return errorResponseNoRedirect(code, description, status);
        }
    }

    private String redirectUri(SecurityEnvironment env) {
        for (Map.Entry<String, List<String>> entry : env.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase("host") && !entry.getValue().isEmpty()) {
                String firstHost = entry.getValue().get(0);
                return oidcConfig.redirectUriWithHost(env.transport() + "://" + firstHost);
            }
        }

        return oidcConfig.redirectUriWithHost();
    }

    private CompletionStage<AuthenticationResponse> failOrAbstain(String message) {
        if (optional) {
            return CompletableFuture.completedFuture(AuthenticationResponse.builder()
                                                             .status(SecurityResponse.SecurityStatus.ABSTAIN)
                                                             .description(message)
                                                             .build());
        } else {
            return CompletableFuture.completedFuture(AuthenticationResponse.builder()
                                                             .status(AuthenticationResponse.SecurityStatus.FAILURE)
                                                             .description(message)
                                                             .build());
        }
    }

    private AuthenticationResponse errorResponseNoRedirect(String code, String description, Http.Status status) {
        if (optional) {
            return AuthenticationResponse.builder()
                    .status(SecurityResponse.SecurityStatus.ABSTAIN)
                    .description(description)
                    .build();
        }
        if (null == code) {
            return AuthenticationResponse.builder()
                    .status(SecurityResponse.SecurityStatus.FAILURE)
                    .statusCode(Http.Status.UNAUTHORIZED_401.code())
                    .responseHeader(Http.Header.WWW_AUTHENTICATE, "Bearer realm=\"" + oidcConfig.realm() + "\"")
                    .description(description)
                    .build();
        } else {
            return AuthenticationResponse.builder()
                    .status(SecurityResponse.SecurityStatus.FAILURE)
                    .statusCode(status.code())
                    .responseHeader(Http.Header.WWW_AUTHENTICATE, errorHeader(code, description))
                    .description(description)
                    .build();
        }
    }

    private int redirectAttempt(String state) {
        if (state.contains("?")) {
            // there are parameters
            Matcher matcher = attemptPattern.matcher(state);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }

        return 1;
    }

    private String errorHeader(String code, String description) {
        return "Bearer realm=\"" + oidcConfig.realm() + "\", error=\"" + code + "\", error_description=\"" + description + "\"";
    }

    private String origUri(ProviderRequest providerRequest) {
        List<String> origUri = providerRequest.env().headers()
                .getOrDefault(Security.HEADER_ORIG_URI, List.of());

        if (origUri.isEmpty()) {
            origUri = List.of(providerRequest.env().targetUri().getPath());
        }

        return origUri.get(0);
    }

    private String encodeState(String state) {
        return URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    private Single<AuthenticationResponse> validateToken(ProviderRequest providerRequest, String token) {
        SignedJwt signedJwt;
        try {
            signedJwt = SignedJwt.parseToken(token);
        } catch (Exception e) {
            //invalid token
            LOGGER.log(Level.FINEST, "Could not parse inbound token", e);
            return Single.just(AuthenticationResponse.failed("Invalid token", e));
        }

        return jwtValidator.apply(signedJwt, Errors.collector())
                .map(it -> processValidationResult(providerRequest,
                                                   signedJwt,
                                                   it))
                .onErrorResume(t -> {
                    LOGGER.log(Level.FINEST, "Failed to validate request", t);
                    return AuthenticationResponse.failed("Failed to validate JWT", t);
                });
    }

    private AuthenticationResponse processValidationResult(ProviderRequest providerRequest,
                                                           SignedJwt signedJwt,
                                                           Errors.Collector collector) {
        Jwt jwt = signedJwt.getJwt();
        Errors errors = collector.collect();
        Errors validationErrors = jwt.validate(oidcConfig.issuer(), oidcConfig.audience());

        if (errors.isValid() && validationErrors.isValid()) {

            errors.log(LOGGER);
            Subject subject = buildSubject(jwt, signedJwt);

            Set<String> scopes = subject.grantsByType("scope")
                    .stream()
                    .map(Grant::getName)
                    .collect(Collectors.toSet());

            // make sure we have the correct scopes
            Set<String> expectedScopes = expectedScopes(providerRequest);
            List<String> missingScopes = new LinkedList<>();
            for (String expectedScope : expectedScopes) {
                if (!scopes.contains(expectedScope)) {
                    missingScopes.add(expectedScope);
                }
            }

            if (missingScopes.isEmpty()) {
                return AuthenticationResponse.success(subject);
            } else {
                return errorResponse(providerRequest,
                                     Http.Status.FORBIDDEN_403,
                                     "insufficient_scope",
                                     "Scopes " + missingScopes + " are missing");
            }
        } else {
            if (LOGGER.isLoggable(Level.FINEST)) {
                // only log errors when details requested
                errors.log(LOGGER);
                validationErrors.log(LOGGER);
            }
            return errorResponse(providerRequest, Http.Status.UNAUTHORIZED_401, "invalid_token", "Token not valid");
        }
    }

    @Override
    public boolean isOutboundSupported(ProviderRequest providerRequest,
                                       SecurityEnvironment outboundEnv,
                                       EndpointConfig outboundConfig) {
        if (!propagate) {
            return false;
        }

        return this.outboundConfig.findTarget(outboundEnv)
                .propagate;
    }

    @Override
    public CompletionStage<OutboundSecurityResponse> outboundSecurity(ProviderRequest providerRequest,
                                                                      SecurityEnvironment outboundEnv,
                                                                      EndpointConfig outboundEndpointConfig) {
        Optional<Subject> user = providerRequest.securityContext().user();

        if (user.isPresent()) {
            // we do have a user, let's see if we can propagate
            Subject subject = user.get();
            Optional<TokenCredential> tokenCredential = subject.publicCredential(TokenCredential.class);
            if (tokenCredential.isPresent()) {
                String tokenContent = tokenCredential.get()
                        .token();

                OidcOutboundTarget target = outboundConfig.findTarget(outboundEnv);
                boolean enabled = target.propagate;

                if (enabled) {
                    Map<String, List<String>> headers = new HashMap<>(outboundEnv.headers());
                    target.tokenHandler.header(headers, tokenContent);
                    return CompletableFuture.completedFuture(OutboundSecurityResponse.withHeaders(headers));
                }
            }
        }

        return CompletableFuture.completedFuture(OutboundSecurityResponse.empty());
    }

    private Subject buildSubject(Jwt jwt, SignedJwt signedJwt) {
        Principal principal = buildPrincipal(jwt);

        TokenCredential.Builder builder = TokenCredential.builder();
        jwt.issueTime().ifPresent(builder::issueTime);
        jwt.expirationTime().ifPresent(builder::expTime);
        jwt.issuer().ifPresent(builder::issuer);
        builder.token(signedJwt.tokenContent());
        builder.addToken(Jwt.class, jwt);
        builder.addToken(SignedJwt.class, signedJwt);

        Subject.Builder subjectBuilder = Subject.builder()
                .principal(principal)
                .addPublicCredential(TokenCredential.class, builder.build());

        if (useJwtGroups) {
            Optional<List<String>> userGroups = jwt.userGroups();
            userGroups.ifPresent(groups -> groups.forEach(group -> subjectBuilder.addGrant(Role.create(group))));
        }

        Optional<List<String>> scopes = jwt.scopes();
        scopes.ifPresent(scopeList -> scopeList.forEach(scope -> subjectBuilder.addGrant(Grant.builder()
                                                                                                 .name(scope)
                                                                                                 .type("scope")
                                                                                                 .build())));

        return subjectBuilder.build();

    }

    private Principal buildPrincipal(Jwt jwt) {
        String subject = jwt.subject()
                .orElseThrow(() -> new JwtException("JWT does not contain subject claim, cannot create principal."));

        String name = jwt.preferredUsername()
                .orElse(subject);

        Principal.Builder builder = Principal.builder();

        builder.name(name)
                .id(subject);

        jwt.payloadClaims()
                .forEach((key, jsonValue) -> builder.addAttribute(key, JwtUtil.toObject(jsonValue)));

        jwt.email().ifPresent(value -> builder.addAttribute("email", value));
        jwt.emailVerified().ifPresent(value -> builder.addAttribute("email_verified", value));
        jwt.locale().ifPresent(value -> builder.addAttribute("locale", value));
        jwt.familyName().ifPresent(value -> builder.addAttribute("family_name", value));
        jwt.givenName().ifPresent(value -> builder.addAttribute("given_name", value));
        jwt.fullName().ifPresent(value -> builder.addAttribute("full_name", value));

        return builder.build();
    }

    /**
     * Builder for {@link io.helidon.security.providers.oidc.OidcProvider}.
     */
    @Configured(prefix = OidcProviderService.PROVIDER_CONFIG_KEY,
                description = "Open ID Connect security provider",
                provides = {AuthenticationProvider.class, SecurityProvider.class})
    public static final class Builder implements io.helidon.common.Builder<Builder, OidcProvider> {
        private boolean optional = false;
        private OidcConfig oidcConfig;
        // identity propagation is disabled by default. In general we should not reuse the same token
        // for outbound calls, unless it is the same audience
        private Boolean propagate;
        private boolean useJwtGroups = true;
        private OutboundConfig outboundConfig;
        private TokenHandler defaultOutboundHandler = TokenHandler.builder()
                .tokenHeader("Authorization")
                .tokenPrefix("Bearer ")
                .build();

        @Override
        public OidcProvider build() {
            if (null == oidcConfig) {
                throw new IllegalArgumentException("OidcConfig must be configured");
            }
            if (outboundConfig == null) {
                outboundConfig = OutboundConfig.builder()
                        .build();
            }
            if (propagate == null) {
                propagate = (outboundConfig.targets().size() > 0);
            }
            return new OidcProvider(this, new OidcOutboundConfig(outboundConfig, defaultOutboundHandler));
        }

        /**
         * Update this builder with configuration.
         * Only updates information that was not explicitly set.
         *
         * The following configuration options are used:
         *
         * <table class="config">
         * <caption>Optional configuration parameters</caption>
         * <tr>
         *     <th>key</th>
         *     <th>default value</th>
         *     <th>description</th>
         * </tr>
         * <tr>
         *     <td>&nbsp;</td>
         *     <td>&nbsp;</td>
         *     <td>The current config node is used to construct {@link io.helidon.security.providers.oidc.common.OidcConfig}.</td>
         * </tr>
         * <tr>
         *     <td>propagate</td>
         *     <td>false</td>
         *     <td>Whether to propagate token (overall configuration). If set to false, propagation will
         *     not be done at all.</td>
         * </tr>
         * <tr>
         *     <td>outbound</td>
         *     <td>&nbsp;</td>
         *     <td>Configuration of {@link io.helidon.security.providers.common.OutboundConfig}.
         *     In addition you can use {@code propagate} to disable propagation for an outbound target,
         *     and {@code token} to configure outbound {@link io.helidon.security.util.TokenHandler} for an
         *     outbound target. Default token handler uses {@code Authorization} header with a {@code bearer } prefix</td>
         * </tr>
         * </table>
         *
         * @param config OIDC provider configuration
         * @return updated builder instance
         */
        public Builder config(Config config) {
            config.get("optional").asBoolean().ifPresent(this::optional);
            if (null == oidcConfig) {
                if (config.get("identity-uri").exists()) {
                    oidcConfig = OidcConfig.create(config);
                }
            }
            config.get("propagate").asBoolean().ifPresent(this::propagate);
            if (null == outboundConfig) {
                // the OutboundConfig.create() expects the provider configuration, not the outbound configuration
                config.get("outbound").ifExists(outbound -> outboundConfig(OutboundConfig.create(config)));
            }
            config.get("use-jwt-groups").asBoolean().ifPresent(this::useJwtGroups);

            return this;
        }

        /**
         * Whether to propagate identity.
         *
         * @param propagate whether to propagate identity (true) or not (false)
         * @return updated builder instance
         */
        @ConfiguredOption("false")
        public Builder propagate(boolean propagate) {
            this.propagate = propagate;
            return this;
        }

        /**
         * Configuration of outbound rules.
         *
         * @param config outbound configuration
         *
         * @return updated builder instance
         */
        @ConfiguredOption(mergeWithParent = true)
        public Builder outboundConfig(OutboundConfig config) {
            this.outboundConfig = config;
            return this;
        }

        /**
         * Configuration of OIDC (Open ID Connect).
         *
         * @param config OIDC configuration for this provider
         *
         * @return updated builder instance
         */
        @ConfiguredOption(mergeWithParent = true)
        public Builder oidcConfig(OidcConfig config) {
            this.oidcConfig = config;
            return this;
        }

        /**
         * Whether authentication is required.
         * By default, request will fail if the authentication cannot be verified.
         * If set to true, request will process and this provider will abstain.
         *
         * @param optional whether authentication is optional (true) or required (false)
         * @return updated builder instance
         */
        @ConfiguredOption("false")
        public Builder optional(boolean optional) {
            this.optional = optional;
            return this;
        }

        /**
         * Claim {@code groups} from JWT will be used to automatically add
         *  groups to current subject (may be used with {@link jakarta.annotation.security.RolesAllowed} annotation).
         *
         * @param useJwtGroups whether to use {@code groups} claim from JWT to retrieve roles
         * @return updated builder instance
         */
        @ConfiguredOption("true")
        public Builder useJwtGroups(boolean useJwtGroups) {
            this.useJwtGroups = useJwtGroups;
            return this;
        }
    }

    private static final class OidcOutboundConfig {
        private final Map<OutboundTarget, OidcOutboundTarget> targetCache = new HashMap<>();
        private final OutboundConfig outboundConfig;
        private final TokenHandler defaultTokenHandler;
        private final OidcOutboundTarget defaultTarget;

        private OidcOutboundConfig(OutboundConfig outboundConfig, TokenHandler defaultTokenHandler) {
            this.outboundConfig = outboundConfig;
            this.defaultTokenHandler = defaultTokenHandler;

            this.defaultTarget = new OidcOutboundTarget(true, defaultTokenHandler);
        }

        private boolean hasOutbound() {
            return outboundConfig.targets().size() > 0;
        }

        private OidcOutboundTarget findTarget(SecurityEnvironment env) {
            return outboundConfig.findTarget(env)
                    .map(value -> targetCache.computeIfAbsent(value, outboundTarget -> {
                        boolean propagate = outboundTarget.getConfig()
                                .flatMap(cfg -> cfg.get("propagate").asBoolean().asOptional())
                                .orElse(true);
                        TokenHandler handler = outboundTarget.getConfig()
                                .flatMap(cfg ->
                                                 DeprecatedConfig.get(cfg, "outbound-token", "token")
                                                         .as(TokenHandler::create)
                                                         .asOptional())
                                .orElse(defaultTokenHandler);
                        return new OidcOutboundTarget(propagate, handler);
                    })).orElse(defaultTarget);
        }
    }

    private static final class OidcOutboundTarget {
        private final boolean propagate;
        private final TokenHandler tokenHandler;

        private OidcOutboundTarget(boolean propagate, TokenHandler handler) {
            this.propagate = propagate;
            tokenHandler = handler;
        }
    }
}

