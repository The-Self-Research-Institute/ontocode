<#import "template.ftl" as layout>
<@layout.registrationLayout
    displayMessage=!messagesPerField.existsError('firstName','lastName','email','username','password','password-confirm');
    section>

    <#if section = "header">
        ${msg("registerTitle")}

    <#elseif section = "form">
        <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.registrationAction}" method="post">

            <!-- Personal info section -->
            <div class="oc-section-label">Personal Info</div>

            <!-- First Name + Last Name side by side -->
            <div class="oc-two-col">
                <div class="${properties.kcFormGroupClass!}">
                    <label for="firstName" class="${properties.kcLabelClass!}">${msg("firstName")}</label>
                    <input
                        type="text"
                        id="firstName"
                        name="firstName"
                        value="${(register.formData.firstName!'')}"
                        class="${properties.kcInputClass!}"
                        autocomplete="given-name"
                        aria-invalid="<#if messagesPerField.existsError('firstName')>true</#if>"
                    />
                    <#if messagesPerField.existsError('firstName')>
                        <span class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('firstName'))?no_esc}
                        </span>
                    </#if>
                </div>

                <div class="${properties.kcFormGroupClass!}">
                    <label for="lastName" class="${properties.kcLabelClass!}">${msg("lastName")}</label>
                    <input
                        type="text"
                        id="lastName"
                        name="lastName"
                        value="${(register.formData.lastName!'')}"
                        class="${properties.kcInputClass!}"
                        autocomplete="family-name"
                        aria-invalid="<#if messagesPerField.existsError('lastName')>true</#if>"
                    />
                    <#if messagesPerField.existsError('lastName')>
                        <span class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('lastName'))?no_esc}
                        </span>
                    </#if>
                </div>
            </div>

            <!-- Email -->
            <div class="${properties.kcFormGroupClass!}">
                <label for="email" class="${properties.kcLabelClass!}">${msg("email")}</label>
                <div class="oc-input-wrap">
                    <svg class="oc-input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg>
                    <input
                        type="email"
                        id="email"
                        name="email"
                        value="${(register.formData.email!'')}"
                        class="${properties.kcInputClass!}"
                        autocomplete="email"
                        aria-invalid="<#if messagesPerField.existsError('email')>true</#if>"
                    />
                </div>
                <#if messagesPerField.existsError('email')>
                    <span class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('email'))?no_esc}
                    </span>
                </#if>
            </div>

            <!-- Username (only if email is not used as username) -->
            <#if !realm.registrationEmailAsUsername>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="username" class="${properties.kcLabelClass!}">${msg("username")}</label>
                    <input
                        type="text"
                        id="username"
                        name="username"
                        value="${(register.formData.username!'')}"
                        class="${properties.kcInputClass!}"
                        autocomplete="username"
                        aria-invalid="<#if messagesPerField.existsError('username')>true</#if>"
                    />
                    <#if messagesPerField.existsError('username')>
                        <span class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('username'))?no_esc}
                        </span>
                    </#if>
                </div>
            </#if>

            <!-- Account info section -->
            <div class="oc-section-label">Account Security</div>

            <!-- Password + Confirm Password side by side -->
            <#if passwordRequired??>
                <div class="oc-two-col">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="password" class="${properties.kcLabelClass!}">${msg("password")}</label>
                        <div class="oc-pwd-group">
                            <svg class="oc-input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                            <input
                                type="password"
                                id="password"
                                name="password"
                                class="${properties.kcInputClass!}"
                                autocomplete="new-password"
                                aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>"
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
                        <#if messagesPerField.existsError('password')>
                            <span class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                                ${kcSanitize(messagesPerField.get('password'))?no_esc}
                            </span>
                        </#if>
                    </div>

                    <div class="${properties.kcFormGroupClass!}">
                        <label for="password-confirm" class="${properties.kcLabelClass!}">${msg("passwordConfirm")}</label>
                        <div class="oc-pwd-group">
                            <svg class="oc-input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                            <input
                                type="password"
                                id="password-confirm"
                                name="password-confirm"
                                class="${properties.kcInputClass!}"
                                autocomplete="new-password"
                                aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>"
                            />
                            <#if passwordVisible??>
                                <button
                                    class="oc-pwd-toggle"
                                    type="button"
                                    aria-label="${msg('confirmPasswordHide')}"
                                    aria-controls="password-confirm"
                                    data-password-toggle
                                    data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}"
                                    data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                                    data-label-show="${msg('showPassword')}"
                                    data-label-hide="${msg('hidePassword')}">
                                    <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                                </button>
                            </#if>
                        </div>
                        <#if messagesPerField.existsError('password-confirm')>
                            <span class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                                ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
                            </span>
                        </#if>
                    </div>
                </div>
            </#if>

            <!-- Submit -->
            <div class="oc-submit-row">
                <button
                    class="oc-btn-primary"
                    type="submit">
                    ${msg("doRegister")}
                </button>
            </div>

            <!-- Back to login -->
            <div class="oc-back-to-login">
                <a href="${url.loginUrl}">&larr; ${msg("backToLogin")}</a>
            </div>

        </form>
    </#if>
</@layout.registrationLayout>
