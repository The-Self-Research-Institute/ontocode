# OntoCode - Keycloak Custom Theme

## Overview
This directory contains a custom Keycloak theme for the OntoCode platform with dynamic branding and modern UI design.

## Theme Structure
```
keycloak-themes/
└── ontocode/
    ├── login/                      # Login pages theme
    │   ├── theme.properties
    │   └── resources/
    │       ├── css/
    │       │   └── ontocode.css   # Custom styles
    │       └── img/
    │           ├── ontocode-logo.png
    │           └── favicon.ico
    └── account/                    # Account management theme
        ├── theme.properties
        └── resources/
            └── css/
                └── account.css
```

## Features
- 🎨 Modern gradient design with OntoCode branding
- 📱 Fully responsive for mobile and desktop
- 🎯 Custom color scheme matching OntoCode platform
- ✨ Smooth animations and transitions
- 🔒 Professional login and registration forms
- 🌍 Multi-language support
- 🔐 Social login provider styling

## Color Palette
- **Primary**: #667eea (Purple Blue)
- **Secondary**: #764ba2 (Purple)
- **Accent**: #f093fb (Pink)
- **Background**: Gradient from Primary to Secondary
- **Text**: #333333

## Installation
The theme is automatically mounted into the Keycloak Docker container via docker-compose.keycloak.yml

## Applying the Theme

### Via Keycloak Admin Console:
1. Access Keycloak at http://localhost:9080
2. Login with admin/admin
3. Select your realm (or create "ontocode" realm)
4. Go to Realm Settings → Themes
5. Select "ontocode" for:
   - Login Theme
   - Account Theme
6. Save changes

### Via Keycloak CLI (kcadm):
```bash
docker exec -it ontocode-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin --password admin

docker exec -it ontocode-keycloak /opt/keycloak/bin/kcadm.sh update realms/ontocode \
  -s loginTheme=ontocode -s accountTheme=ontocode
```

## Adding Custom Logo
Replace `keycloak-themes/ontocode/login/resources/img/ontocode-logo.png` with your logo (recommended size: 200x60px)

## Customization
Edit `keycloak-themes/ontocode/login/resources/css/ontocode.css` to modify:
- Colors (CSS variables at the top)
- Fonts
- Spacing
- Animations

## Development
After making changes:
1. Restart Keycloak container: `docker-compose -f docker-compose.keycloak.yml restart keycloak`
2. Clear browser cache
3. Refresh login page

## Troubleshooting
- **Theme not appearing**: Check volume mount in docker-compose.keycloak.yml
- **Styles not loading**: Clear browser cache, check console for CSS errors
- **Logo not showing**: Verify image path and file exists in resources/img/

## Browser Support
- Chrome/Edge (latest)
- Firefox (latest)
- Safari (latest)
- Mobile browsers

## License
Part of the OntoCode platform
