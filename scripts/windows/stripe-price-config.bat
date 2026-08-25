@echo off
REM Stripe Price Configuration Script for Windows
REM 
REM Usage:
REM   stripe-price-config.bat <STRIPE_SECRET_KEY>
REM   stripe-price-config.bat <STRIPE_SECRET_KEY> validate
REM   stripe-price-config.bat <STRIPE_SECRET_KEY> list
REM   stripe-price-config.bat <STRIPE_SECRET_KEY> create

setlocal enabledelayedexpansion

if "%~1"=="" (
    echo.
    echo ❌ Error: Stripe API key required
    echo Usage: stripe-price-config.bat ^<STRIPE_SECRET_KEY^> [list^|validate^|create]
    echo.
    exit /b 1
)

set STRIPE_KEY=%~1
set COMMAND=%~2
if "!COMMAND!"=="" set COMMAND=list

REM Check if Node.js is installed
where node >nul 2>nul
if errorlevel 1 (
    echo ❌ Error: Node.js not found. Please install Node.js from https://nodejs.org/
    exit /b 1
)

REM Check if stripe package is installed
npm list stripe >nul 2>nul
if errorlevel 1 (
    echo 📦 Installing Stripe SDK...
    call npm install stripe
)

REM Create temporary Node.js script and run it
set TEMP_SCRIPT=%TEMP%\stripe-config-temp.js
(
    echo const stripe = require('stripe');
    echo const stripeClient = stripe('%STRIPE_KEY%');
    echo.
    echo const command = '%COMMAND%';
    echo.
    echo async function listAllPrices(^) {
    echo   console.log('\n📋 Fetching all Stripe products and prices...\n');
    echo   const products = await stripeClient.products.list({ limit: 100 });
    echo   if (products.data.length === 0^) {
    echo     console.log('⚠️  No products found');
    echo     return;
    echo   }
    echo   for (const product of products.data^) {
    echo     if (product.deleted^) continue;
    echo     console.log(`\n📦 Product: ${product.name} (${product.id})`);
    echo     const prices = await stripeClient.prices.list({ product: product.id, limit: 100 });
    echo     if (prices.data.length === 0^) {
    echo       console.log('   └─ No prices');
    echo       continue;
    echo     }
    echo     prices.data.forEach((p, i^) => {
    echo       const interval = p.recurring ? p.recurring.interval : 'one-time';
    echo       const amount = p.unit_amount ? (p.unit_amount / 100^) : 'custom';
    echo       const currency = p.currency.toUpperCase(^);
    echo       console.log(`   ├─ ${p.id}`);
    echo       console.log(`      └─ ${amount} ${currency} / ${interval}`);
    echo     });
    echo   }
    echo }
    echo.
    echo async function validatePrices(^) {
    echo   console.log('\n🔍 Validating prices...\n');
    echo   const priceIds = {
    echo     'PRO_MONTHLY': process.env.STRIPE_PRICE_PRO_MONTHLY,
    echo     'PRO_YEARLY': process.env.STRIPE_PRICE_PRO_YEARLY,
    echo     'ENTERPRISE_MONTHLY': process.env.STRIPE_PRICE_ENTERPRISE_MONTHLY,
    echo     'ENTERPRISE_YEARLY': process.env.STRIPE_PRICE_ENTERPRISE_YEARLY
    echo   };
    echo   let valid = true;
    echo   for (const [name, pid] of Object.entries(priceIds^)^) {
    echo     if (!pid^) {
    echo       console.log(`❌ ${name}: NOT SET`);
    echo       valid = false;
    echo       continue;
    echo     }
    echo     try {
    echo       const price = await stripeClient.prices.retrieve(pid);
    echo       console.log(`✅ ${name}: ${pid}`);
    echo     } catch (e^) {
    echo       console.log(`❌ ${name}: ${pid} - INVALID`);
    echo       valid = false;
    echo     }
    echo   }
    echo   return valid;
    echo }
    echo.
    echo (async (^) => {
    echo   try {
    echo     if (command === 'list'^ || command === '^) await listAllPrices(^);
    echo     else if (command === 'validate'^) await validatePrices(^);
    echo     else console.log('Unknown command: ' + command^);
    echo   } catch (e^) {
    echo     console.error('Error:', e.message^);
    echo     process.exit(1^);
    echo   }
    echo }^)(^);
) > !TEMP_SCRIPT!

call node !TEMP_SCRIPT!
set EXIT_CODE=%ERRORLEVEL%
del /f /q !TEMP_SCRIPT! >nul 2>nul

exit /b !EXIT_CODE!
