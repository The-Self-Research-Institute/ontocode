<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html<#if realm.internationalizationEnabled> lang="${locale.currentLanguageTag}"</#if>>
<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <meta name="robots" content="noindex, nofollow">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link rel="icon" href="${url.resourcesPath}/img/favicon.svg" type="image/svg+xml"/>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <#if properties.stylesCommon?has_content>
        <#list properties.stylesCommon?split(' ') as style>
            <link href="${url.resourcesCommonPath}/${style}" rel="stylesheet"/>
        </#list>
    </#if>
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet"/>
        </#list>
    </#if>
    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
</head>
<body>
<div class="oc-page">
    <div class="oc-card">

        <!-- Logo -->
        <div class="oc-logo">
            <img src="${url.resourcesPath}/img/ontocode-logo.svg" alt="OntoCode" />
        </div>

        <!-- Title & subtitle -->
        <h1 class="oc-title"><#nested "header"></h1>
        <p class="oc-subtitle">${realm.displayName!"OntoCode"}</p>

        <!-- Locale switcher -->
        <#if realm.internationalizationEnabled && locale.supported?size gt 1>
            <div class="oc-locale">
                <select onchange="window.location.href=this.value">
                    <#list locale.supported as l>
                        <option value="${l.url}"<#if l.languageTag == locale.currentLanguageTag> selected</#if>>${l.label}</option>
                    </#list>
                </select>
            </div>
        </#if>

        <!-- Flash messages -->
        <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
            <div class="oc-alert oc-alert--${message.type}">
                ${kcSanitize(message.summary)?no_esc}
            </div>
        </#if>

        <!-- Form -->
        <div class="oc-form-body">
            <#nested "form">
        </div>

        <!-- Register / info footer -->
        <#if displayInfo>
            <div class="oc-footer">
                <#nested "info">
            </div>
        </#if>

    </div><!-- /.oc-card -->
</div><!-- /.oc-page -->
</body>
</html>
</#macro>
