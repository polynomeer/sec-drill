package secdrill.controlplane.identity.web

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import secdrill.controlplane.identity.AuthProperties
import secdrill.controlplane.identity.AuthSessionService
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.identity.WorkloadCredentialService

/**
 * Two separate chains (15, 19): `/ops` routes accepts only operator bearer tokens; everything else accepts only
 * learner cookies. Spring's CSRF support is replaced by [OriginCsrfFilter], which implements the contract's
 * Origin + `X-CSRF-Token` rule. Default deny: anything not listed requires authentication.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig {
    /** Runner and gateway workloads; learner cookies and operator tokens are not accepted here. */
    @Bean
    @Order(0)
    fun workloadChain(http: HttpSecurity, workloads: WorkloadCredentialService, errors: ErrorEnvelopeWriter): SecurityFilterChain =
        http.securityMatcher("/internal/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .addFilterBefore(WorkloadBearerFilter(workloads), AnonymousAuthenticationFilter::class.java)
            .authorizeHttpRequests {
                it.requestMatchers("/internal/v1/gateway/**").hasAuthority(ROLE_GATEWAY)
                it.requestMatchers("/internal/v1/lab-jobs/**", "/internal/v1/labs/**", "/internal/v1/grade-jobs/**").hasAuthority(ROLE_AGENT)
                it.anyRequest().denyAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> errors.unauthenticated(response) }
                it.accessDeniedHandler { _, response, _ -> errors.forbidden(response) }
            }
            .build()

    @Bean
    @Order(1)
    fun operatorChain(http: HttpSecurity, operators: OperatorAccessService, errors: ErrorEnvelopeWriter): SecurityFilterChain =
        http.securityMatcher("/ops/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .addFilterBefore(OperatorBearerFilter(operators, errors), AnonymousAuthenticationFilter::class.java)
            .authorizeHttpRequests { it.anyRequest().hasAuthority(ROLE_OPERATOR) }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> errors.unauthenticated(response) }
                it.accessDeniedHandler { _, response, _ -> errors.forbidden(response) }
            }
            .build()

    @Bean
    @Order(2)
    fun learnerChain(
        http: HttpSecurity,
        sessions: AuthSessionService,
        properties: AuthProperties,
        errors: ErrorEnvelopeWriter,
        oidcSuccess: OidcLoginSuccessHandler,
        registrations: ObjectProvider<ClientRegistrationRepository>,
    ): SecurityFilterChain {
        http.csrf { it.disable() }
            .requestCache { it.disable() }
            // Authentication lives only for the request: a server session must never outlive token expiry or
            // revocation. HTTP sessions exist only for the OIDC handshake (state, nonce, PKCE verifier) and are
            // invalidated on success.
            .securityContext { it.securityContextRepository(RequestAttributeSecurityContextRepository()) }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                it.sessionAuthenticationStrategy(NullAuthenticatedSessionStrategy())
            }
            .addFilterBefore(AccessCookieFilter(sessions), AnonymousAuthenticationFilter::class.java)
            .addFilterAfter(OriginCsrfFilter(properties, errors), AccessCookieFilter::class.java)
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                it.requestMatchers("/v1/auth/refresh", "/v1/auth/dev-login").permitAll()
                it.requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                // Minimal static UI (T07): public files only; every API call it makes is authenticated.
                it.requestMatchers(org.springframework.http.HttpMethod.GET, "/app/**").permitAll()
                it.anyRequest().hasAuthority(ROLE_LEARNER)
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> errors.unauthenticated(response) }
                it.accessDeniedHandler { _, response, _ -> errors.forbidden(response) }
            }
            // Same-origin scripts only, no framing, no plugins (the UI has no inline script or style).
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'") }
            }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }

        registrations.ifAvailable { repository ->
            val resolver = DefaultOAuth2AuthorizationRequestResolver(repository, "/oauth2/authorization")
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
            http.oauth2Login { login ->
                login.authorizationEndpoint { it.authorizationRequestResolver(resolver) }
                login.successHandler(oidcSuccess)
                login.failureHandler { _, response, _ -> errors.unauthenticated(response) }
            }
        }
        return http.build()
    }
}
