<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        ${msg("errorTitle")}
    <#elseif section = "form">
        <div id="kc-error-message">
            <p class="instruction">${kcSanitize(message.summary)?no_esc}</p>

            <#-- Show Sign Out button when the user is already logged in as someone else -->
            <#if message.summary?contains("already authenticated") || message.summary?contains("already logged") || message.summary?contains("sign out")>
                <div style="margin-top: 24px; display: flex; flex-direction: column; align-items: center; gap: 12px;">
                    <#-- Keycloak logout: clears the SSO session, then redirects back to the login page -->
                    <a href="${baseUrl}/realms/${realm.name}/protocol/openid-connect/logout?post_logout_redirect_uri=${url.loginUrl?url}&client_id=${client.clientId!''}"
                       id="kc-signout"
                       class="pf-c-button pf-m-primary pf-m-block btn-lg"
                       style="text-align:center; text-decoration:none;">
                        ${msg("doSignOut")}
                    </a>
                    <a href="${url.loginUrl}" class="pf-c-button pf-m-link pf-m-block" style="text-align:center;">
                        ${msg("backToLogin")}
                    </a>
                </div>
            <#else>
                <#if skipLink??>
                <#else>
                    <#if pageRedirectUri?has_content>
                        <p><a href="${pageRedirectUri}">${kcSanitize(msg("backToApplication"))?no_esc}</a></p>
                    <#elseif actionUri?has_content>
                        <p><a href="${actionUri}">${kcSanitize(msg("proceedWithAction"))?no_esc}</a></p>
                    <#elseif (client.baseUrl)?has_content>
                        <p><a href="${client.baseUrl}">${kcSanitize(msg("backToApplication"))?no_esc}</a></p>
                    </#if>
                </#if>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
