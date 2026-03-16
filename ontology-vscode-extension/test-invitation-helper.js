/**
 * Invitation Testing Helper Script for test-web
 * 
 * USAGE:
 * 1. Run: npm run test-web
 * 2. Open OntoCode editor window
 * 3. Open browser console (F12)
 * 4. Paste this entire file into the console
 * 5. Call: testInvitation('YOUR_TOKEN_HERE')
 * 
 * This script sends the invitation token directly to the OntoCode webview iframe.
 */

function testInvitation(token, email = null) {
    console.log('🎫 Testing invitation flow with token:', token);
    
    // Check deployment type first
    const deploymentType = localStorage.getItem('deploymentType');
    console.log('📍 Current deployment type:', deploymentType || 'cloud (default)');
    
    if (!deploymentType || deploymentType === 'cloud') {
        console.warn('⚠️  WARNING: Using cloud backend! For local testing, run:');
        console.warn('   localStorage.setItem("deploymentType", "self-hosted");');
        console.warn('   location.reload();');
    }
    
    // Find the OntoCode webview iframe
    const webviewFrame = document.querySelector('iframe[title*="OntoCode"]');
    
    if (!webviewFrame) {
        console.error('❌ OntoCode webview not found!');
        console.log('💡 Make sure:');
        console.log('   1. You opened the OntoCode editor (Ctrl+Shift+P → "OntoCode: Edit with OntoCode")');
        console.log('   2. The webview is fully loaded');
        return false;
    }
    
    // Send invitation token to webview
    const message = {
        type: 'invitationToken',
        token: token
    };
    
    if (email) {
        message.email = email;
    }
    
    webviewFrame.contentWindow.postMessage(message, '*');
    
    console.log('✅ Sent invitation token to webview');
    console.log('📧 Token:', token);
    if (email) console.log('📧 Email:', email);
    console.log('⏳ Waiting for InviteAcceptPage to render...');
    console.log('👀 Watch the Network tab to see API call to /api/invitations/details/' + token);
    
    return true;
}

function clearInvitation() {
    console.log('🧹 Clearing invitation state...');
    
    const webviewFrame = document.querySelector('iframe[title*="OntoCode"]');
    
    if (!webviewFrame) {
        console.error('❌ OntoCode webview not found!');
        return false;
    }
    
    webviewFrame.contentWindow.postMessage({
        type: 'clearInvitationState'
    }, '*');
    
    console.log('✅ Sent clear invitation message');
    return true;
}

// Quick test with mock token
function quickTest() {
    const mockToken = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpbnZpdGF0aW9uSWQiOiJ0ZXN0LWlkIiwiZW1haWwiOiJ0ZXN0QGV4YW1wbGUuY29tIiwicm9sZSI6Im1lbWJlciJ9.test-signature';
    return testInvitation(mockToken, 'test@example.com');
}

// Helper to check backend connectivity
function checkBackend() {
    console.log('🔍 Checking backend configuration...\n');
    
    const deploymentType = localStorage.getItem('deploymentType');
    console.log('📍 Deployment type:', deploymentType || 'cloud (default)');
    
    const baseUrl = deploymentType === 'self-hosted' ? 'http://localhost:80' : 'https://ontocodeapi.selfresearch.org';
    console.log('🌐 Base URL:', baseUrl);
    
    // Test connectivity
    console.log('\n🧪 Testing API connectivity...');
    fetch(baseUrl + '/api/auth/health')
        .then(response => {
            if (response.ok) {
                console.log('✅ Backend is accessible!');
            } else {
                console.error('❌ Backend returned:', response.status, response.statusText);
            }
        })
        .catch(err => {
            console.error('❌ Cannot connect to backend:', err.message);
            console.error('   Make sure backend services are running!');
        });
}

// Helper to set local backend
function useLocalBackend() {
    localStorage.setItem('deploymentType', 'self-hosted');
    console.log('✅ Set to use local backend (self-hosted)');
    console.log('🔄 Reloading page...');
    setTimeout(() => location.reload(), 1000);
}

console.log('✅ Invitation testing helper loaded!');
console.log('');
console.log('📖 Available commands:');
console.log('   testInvitation("YOUR_TOKEN")          - Test with your invitation token');
console.log('   testInvitation("TOKEN", "email@...")  - Test with token and email');
console.log('   quickTest()                           - Quick test with mock token');
console.log('   clearInvitation()                     - Clear invitation state');
console.log('   checkBackend()                        - Check backend configuration');
console.log('   useLocalBackend()                     - Switch to local backend and reload');
console.log('');
console.log('💡 Example:');
console.log('   testInvitation("930ead61f3704980804545503ff40ba6")');
console.log('');
console.log('🔧 For local testing, first run:');
console.log('   useLocalBackend()');
