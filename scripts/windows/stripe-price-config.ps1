#!/usr/bin/env pwsh
<#
.SYNOPSIS
Stripe Price Configuration & Verification Script

.DESCRIPTION
Helps configure and verify Stripe prices for the OntoCode billing system.

.PARAMETER StripeKey
Your Stripe Secret API key (sk_live_... or sk_test_...)

.PARAMETER Command
Command to execute: list, validate, or create
- list: Show all products and prices in your Stripe account
- validate: Check if configured prices are valid
- create: Create default products and prices

.EXAMPLE
.\stripe-price-config.ps1 -StripeKey "sk_test_xxxxx" -Command "list"
.\stripe-price-config.ps1 -StripeKey "sk_test_xxxxx" -Command "validate"
.\stripe-price-config.ps1 -StripeKey "sk_test_xxxxx" -Command "create"

#>

param(
    [Parameter(Mandatory = $true)]
    [string]$StripeKey,
    
    [Parameter(Mandatory = $false)]
    [string]$Command = "list"
)

# Check if Node.js is available
$nodeAvailable = $null -ne (Get-Command node -ErrorAction SilentlyContinue)
if (-not $nodeAvailable) {
    Write-Error "❌ Node.js not found. Please install from https://nodejs.org/"
    exit 1
}

# Create a temporary Node.js script
$tempScript = Join-Path $env:TEMP "stripe-config-$([guid]::NewGuid()).js"

$nodeScript = @"
const stripe = require('stripe');
const stripeClient = stripe('$StripeKey');

const command = '$Command';

async function listAllPrices() {
  console.log('\n📋 Fetching all Stripe products and prices...\n');
  
  try {
    const products = await stripeClient.products.list({ limit: 100 });
    
    if (products.data.length === 0) {
      console.log('⚠️  No products found in Stripe account');
      return;
    }

    for (const product of products.data) {
      if (product.deleted) continue;
      
      console.log(\`\n📦 Product: \${product.name} (\${product.id})\`);
      console.log(\`   Type: \${product.type}\`);
      console.log(\`   Active: \${product.active}\`);
      
      const prices = await stripeClient.prices.list({ product: product.id, limit: 100 });
      
      if (prices.data.length === 0) {
        console.log('   └─ No prices');
        continue;
      }

      prices.data.forEach((price, index) => {
        const isLast = index === prices.data.length - 1;
        const prefix = isLast ? '└─ ' : '├─ ';
        
        const interval = price.recurring ? price.recurring.interval : 'one-time';
        const amount = price.unit_amount ? (price.unit_amount / 100) : 'custom';
        const currency = price.currency.toUpperCase();
        
        console.log(\`   \${prefix}Price ID: \${price.id}\`);
        console.log(\`      └─ \${amount} \${currency} / \${interval} (Active: \${price.active})\`);
      });
    }
  } catch (error) {
    console.error('❌ Error fetching prices:', error.message);
    process.exit(1);
  }
}

async function validateConfiguredPrices() {
  console.log('\n🔍 Validating configured price IDs...\n');
  
  const priceIds = {
    'PRO_MONTHLY': process.env.STRIPE_PRICE_PRO_MONTHLY,
    'PRO_YEARLY': process.env.STRIPE_PRICE_PRO_YEARLY,
    'ENTERPRISE_MONTHLY': process.env.STRIPE_PRICE_ENTERPRISE_MONTHLY,
    'ENTERPRISE_YEARLY': process.env.STRIPE_PRICE_ENTERPRISE_YEARLY,
  };
  
  let allValid = true;
  
  for (const [name, priceId] of Object.entries(priceIds)) {
    if (!priceId) {
      console.log(\`❌ \${name}: NOT SET\`);
      allValid = false;
      continue;
    }

    try {
      const price = await stripeClient.prices.retrieve(priceId);
      const interval = price.recurring ? price.recurring.interval : 'one-time';
      const amount = price.unit_amount ? (price.unit_amount / 100) : 'custom';
      const currency = price.currency.toUpperCase();
      
      console.log(\`✅ \${name}: \${priceId}\`);
      console.log(\`   └─ \${amount} \${currency} / \${interval}\`);
    } catch (error) {
      console.log(\`❌ \${name}: \${priceId}\`);
      console.log(\`   └─ Error: \${error.message}\`);
      allValid = false;
    }
  }
  
  if (allValid) {
    console.log('\n✅ All prices are valid!');
  } else {
    console.log('\n❌ Some prices are invalid or not configured');
    console.log('\nSet environment variables:');
    console.log('  \$env:STRIPE_PRICE_PRO_MONTHLY = "price_xxx"');
    console.log('  \$env:STRIPE_PRICE_PRO_YEARLY = "price_xxx"');
    console.log('  \$env:STRIPE_PRICE_ENTERPRISE_MONTHLY = "price_xxx"');
    console.log('  \$env:STRIPE_PRICE_ENTERPRISE_YEARLY = "price_xxx"');
  }
  
  process.exit(allValid ? 0 : 1);
}

async function createDefaultPrices() {
  console.log('\n🔨 Creating default products and prices...\n');
  
  try {
    // Create PRO product
    console.log('Creating PRO product...');
    const proProduct = await stripeClient.products.create({
      name: 'OntoCode Pro',
      type: 'service',
      metadata: { tier: 'pro' },
    });
    console.log(\`✅ Created product: \${proProduct.id}\`);

    // Create PRO monthly price
    console.log('Creating PRO monthly price (\$99/month)...');
    const proMonthly = await stripeClient.prices.create({
      product: proProduct.id,
      unit_amount: 9900,
      currency: 'usd',
      recurring: {
        interval: 'month',
        interval_count: 1,
      },
      metadata: { tier: 'pro', interval: 'monthly' },
    });
    console.log(\`✅ PRO Monthly: \${proMonthly.id}\`);

    // Create PRO yearly price
    console.log('Creating PRO yearly price (\$990/year)...');
    const proYearly = await stripeClient.prices.create({
      product: proProduct.id,
      unit_amount: 99000,
      currency: 'usd',
      recurring: {
        interval: 'year',
        interval_count: 1,
      },
      metadata: { tier: 'pro', interval: 'yearly' },
    });
    console.log(\`✅ PRO Yearly: \${proYearly.id}\`);

    // Create ENTERPRISE product
    console.log('\nCreating ENTERPRISE product...');
    const enterpriseProduct = await stripeClient.products.create({
      name: 'OntoCode Enterprise',
      type: 'service',
      metadata: { tier: 'enterprise' },
    });
    console.log(\`✅ Created product: \${enterpriseProduct.id}\`);

    // Create ENTERPRISE monthly price
    console.log('Creating ENTERPRISE monthly price (\$299/month)...');
    const enterpriseMonthly = await stripeClient.prices.create({
      product: enterpriseProduct.id,
      unit_amount: 29900,
      currency: 'usd',
      recurring: {
        interval: 'month',
        interval_count: 1,
      },
      metadata: { tier: 'enterprise', interval: 'monthly' },
    });
    console.log(\`✅ ENTERPRISE Monthly: \${enterpriseMonthly.id}\`);

    // Create ENTERPRISE yearly price
    console.log('Creating ENTERPRISE yearly price (\$2990/year)...');
    const enterpriseYearly = await stripeClient.prices.create({
      product: enterpriseProduct.id,
      unit_amount: 299000,
      currency: 'usd',
      recurring: {
        interval: 'year',
        interval_count: 1,
      },
      metadata: { tier: 'enterprise', interval: 'yearly' },
    });
    console.log(\`✅ ENTERPRISE Yearly: \${enterpriseYearly.id}\`);

    console.log('\n✅ All prices created successfully!\n');
    console.log('📝 Set these environment variables:\n');
    console.log(\`\\\$env:STRIPE_PRICE_PRO_MONTHLY = '\${proMonthly.id}'\`);
    console.log(\`\\\$env:STRIPE_PRICE_PRO_YEARLY = '\${proYearly.id}'\`);
    console.log(\`\\\$env:STRIPE_PRICE_ENTERPRISE_MONTHLY = '\${enterpriseMonthly.id}'\`);
    console.log(\`\\\$env:STRIPE_PRICE_ENTERPRISE_YEARLY = '\${enterpriseYearly.id}'\n\`);

  } catch (error) {
    console.error('❌ Error creating prices:', error.message);
    process.exit(1);
  }
}

async function main() {
  try {
    switch (command) {
      case 'list':
        await listAllPrices();
        break;
      case 'validate':
        await validateConfiguredPrices();
        break;
      case 'create':
        await createDefaultPrices();
        break;
      default:
        console.error(\`Unknown command: \${command}\`);
        console.error('Available commands: list, validate, create');
        process.exit(1);
    }
  } catch (error) {
    console.error('Fatal error:', error);
    process.exit(1);
  }
}

main();
"@

# Write the temporary script
$nodeScript | Out-File -FilePath $tempScript -Encoding UTF8

# Check if stripe module is installed
Write-Host "📦 Checking Stripe SDK..." -ForegroundColor Cyan
$stripeInstalled = npm list stripe 2>$null | Select-String "stripe" | Select-Object -First 1
if ($null -eq $stripeInstalled) {
    Write-Host "Installing Stripe SDK..." -ForegroundColor Yellow
    npm install stripe --silent | Out-Null
}

# Run the temporary script
Write-Host "Running script...$script" -ForegroundColor Cyan
& node $tempScript
$exitCode = $LASTEXITCODE

# Cleanup
Remove-Item -Path $tempScript -Force -ErrorAction SilentlyContinue

exit $exitCode
