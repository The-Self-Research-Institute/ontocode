<#import "template.ftl" as layout>
<@layout.registrationLayout
    displayMessage=!messagesPerField.existsError('username','password')
    displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??;
    section>

    <#if section = "header">
        ${msg("loginAccountTitle")}

    <#elseif section = "form">
        <#if realm.password>
            <form id="kc-form-login" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">

                <div class="${properties.kcFormGroupClass!}">
                    <label for="username" class="${properties.kcLabelClass!}">
                        <#if !realm.loginWithEmailAllowed>${msg("username")}
                        <#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}
                        <#else>${msg("email")}
                        </#if>
                    </label>
                    <div class="oc-input-wrap">
                        <svg class="oc-input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                        <input
                            tabindex="1"
                            id="username"
                            name="username"
                            value="${(login.username!'')}"
                            type="text"
                            class="${properties.kcInputClass!}"
                            autofocus
                            autocomplete="username"
                            aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                        />
                    </div>
                    <#if messagesPerField.existsError('username','password')>
                        <span class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                            ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                        </span>
                    </#if>
                </div>

                <div class="${properties.kcFormGroupClass!}">
                    <label for="password" class="${properties.kcLabelClass!}">${msg("password")}</label>
                    <div class="oc-pwd-group">
                        <svg class="oc-input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                        <input
                            tabindex="2"
                            id="password"
                            name="password"
                            type="password"
                            class="${properties.kcInputClass!}"
                            autocomplete="current-password"
                            aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                        />
                        <#if passwordVisible??>
                            <button
                                class="oc-pwd-toggle"
                                type="button"
                                aria-label="${msg('showPassword')}"
                                aria-controls="password"
                                data-password-toggle
                                data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}"
                                data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                                data-label-show="${msg('showPassword')}"
                                data-label-hide="${msg('hidePassword')}">
                                <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                            </button>
                        </#if>
                    </div>
                </div>

                <div class="oc-login-options">
                    <#if realm.rememberMe && !usernameEditDisabled??>
                        <label class="oc-remember-label">
                            <input
                                tabindex="3"
                                id="rememberMe"
                                name="rememberMe"
                                type="checkbox"
                                <#if login.rememberMe??>checked</#if>
                            />
                            ${msg("rememberMe")}
                        </label>
                    </#if>
                    <#if realm.resetPasswordAllowed>
                        <a tabindex="4" href="${url.loginResetCredentialsUrl}" class="oc-forgot-link">
                            ${msg("doForgotPassword")}
                        </a>
                    </#if>
                </div>

                <input type="hidden" id="id-hidden-input" name="credentialId"
                       value="<#if auth.selectedCredential?has_content>${auth.selectedCredential}</#if>"/>

                <div class="oc-submit-row">
                    <input
                        tabindex="5"
                        class="oc-btn-primary"
                        type="submit"
                        value="${msg('doLogIn')}"
                    />
                </div>

            </form>
        </#if>

    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div class="oc-footer-row">
                <span class="oc-footer-label">${msg("noAccount")}</span>
                <a href="${url.registrationUrl}" class="oc-create-account">${msg("doRegister")}</a>
            </div>
        </#if>

    </#if>
</@layout.registrationLayout>
