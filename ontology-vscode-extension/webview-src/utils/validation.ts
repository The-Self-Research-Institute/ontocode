/**
 * Frontend Validation Utilities
 * Provides client-side validation to match backend validation rules
 */

export interface ValidationResult {
  isValid: boolean;
  error?: string;
  sanitized?: string;
}

/**
 * Sanitize input for XSS, SQL injection, and path traversal attacks
 * Matches backend SecurityValidationFilter.java logic
 */
export const sanitizeInput = (input: string): ValidationResult => {
  if (!input) {
    return { isValid: false, error: 'Input is required', sanitized: '' };
  }

  // XSS prevention - blocks <, >, javascript:, event handlers
  if (/<|>|javascript:|on\w+=/i.test(input)) {
    return { 
      isValid: false, 
      error: 'Invalid characters detected. HTML tags and scripts are not allowed.',
      sanitized: input.replace(/[<>]/g, '')
    };
  }
  
  // SQL injection prevention - blocks SQL keywords
  if (/(\bSELECT\b|\bINSERT\b|\bUPDATE\b|\bDELETE\b|\bDROP\b|--|\/\*|\*\/)/i.test(input)) {
    return { 
      isValid: false, 
      error: 'Invalid characters detected. SQL commands are not allowed.',
      sanitized: input
    };
  }
  
  // Path traversal prevention - blocks ../, ..\, and encoded variants
  if (/(\.\.[\/\\]|%2e%2e|%252e)/i.test(input)) {
    return { 
      isValid: false, 
      error: 'Invalid path characters detected.',
      sanitized: input
    };
  }

  // Null byte injection prevention
  if (/(%00|\\x00|\\0)/i.test(input)) {
    return { 
      isValid: false, 
      error: 'Invalid null byte detected.',
      sanitized: input
    };
  }
  
  return { isValid: true, sanitized: input.trim() };
};

/**
 * Validate string length
 * Matches backend @Length annotation
 */
export const validateLength = (
  input: string, 
  min: number, 
  max: number,
  fieldName: string = 'Field'
): ValidationResult => {
  const trimmed = input.trim();
  
  if (trimmed.length === 0 && min > 0) {
    return { isValid: false, error: `${fieldName} cannot be empty` };
  }
  
  if (trimmed.length < min) {
    return { isValid: false, error: `${fieldName} must be at least ${min} characters` };
  }
  
  if (input.length > max) {
    return { isValid: false, error: `${fieldName} cannot exceed ${max} characters` };
  }
  
  return { isValid: true };
};

/**
 * Validate email format (RFC 5322 basic check)
 * Matches backend @Email annotation
 */
export const validateEmail = (email: string): ValidationResult => {
  if (!email) {
    return { isValid: false, error: 'Email is required' };
  }

  // Basic RFC 5322 format check
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  
  if (!emailRegex.test(email)) {
    return { isValid: false, error: 'Invalid email format' };
  }
  
  return { isValid: true };
};

/**
 * Validate workspace name
 * Combines multiple backend validations
 */
export const validateWorkspaceName = (name: string): ValidationResult => {
  // Check empty
  if (!name || !name.trim()) {
    return { isValid: false, error: 'Workspace name is required' };
  }

  // Sanitize for XSS/SQL injection
  const sanitized = sanitizeInput(name);
  if (!sanitized.isValid) {
    return sanitized;
  }

  // Check length (1-255 chars)
  const length = validateLength(name, 1, 255, 'Workspace name');
  if (!length.isValid) {
    return length;
  }

  return { isValid: true, sanitized: name.trim() };
};

/**
 * Validate project name
 * Includes additional special character and path traversal checks
 */
export const validateProjectName = (name: string): ValidationResult => {
  // Check empty
  if (!name || !name.trim()) {
    return { isValid: false, error: 'Project name is required' };
  }

  // Sanitize for XSS/SQL injection
  const sanitized = sanitizeInput(name);
  if (!sanitized.isValid) {
    return sanitized;
  }

  // Check length (1-255 chars)
  const length = validateLength(name, 1, 255, 'Project name');
  if (!length.isValid) {
    return length;
  }

  // Block unsafe special characters for file system (matches backend ProjectService)
  const unsafeChars = /[<>:"/\\|?*]/;
  if (unsafeChars.test(name)) {
    return { 
      isValid: false, 
      error: 'Project name cannot contain: < > : " / \\ | ? *'
    };
  }

  return { isValid: true, sanitized: name.trim() };
};

/**
 * Validate description length
 * Matches backend max length of 1000
 */
export const validateDescription = (description: string): ValidationResult => {
  if (!description) {
    return { isValid: true }; // Description is optional
  }

  // Sanitize for XSS
  const sanitized = sanitizeInput(description);
  if (!sanitized.isValid) {
    return sanitized;
  }

  // Check max length
  if (description.length > 1000) {
    return { 
      isValid: false, 
      error: 'Description cannot exceed 1000 characters' 
    };
  }

  return { isValid: true };
};

/**
 * Get maximum workspaces allowed for subscription plan
 * Matches backend WorkspaceController.getMaxWorkspacesForPlan()
 */
export const getMaxWorkspacesForPlan = (plan: string): number => {
  switch (plan.toUpperCase()) {
    case 'FREE':
      return 3;
    case 'PRO':
      return 10;
    case 'ENTERPRISE':
      return Number.MAX_SAFE_INTEGER;
    default:
      return 3; // Default to FREE plan limits
  }
};

/**
 * Get maximum members allowed for subscription plan
 * Matches backend WorkspaceController.getMaxMembersForPlan()
 */
export const getMaxMembersForPlan = (plan: string): number => {
  switch (plan.toUpperCase()) {
    case 'FREE':
      return 3;
    case 'PRO':
      return 10;
    case 'ENTERPRISE':
      return Number.MAX_SAFE_INTEGER;
    default:
      return 3; // Default to FREE plan limits
  }
};

/**
 * Validate role selection
 * Matches backend role enum validation
 */
export const validateRole = (role: string): ValidationResult => {
  const validRoles = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'];
  
  if (!role) {
    return { isValid: false, error: 'Role is required' };
  }

  if (!validRoles.includes(role.toUpperCase())) {
    return { 
      isValid: false, 
      error: `Invalid role. Allowed values: ${validRoles.join(', ')}` 
    };
  }

  return { isValid: true };
};

/**
 * Validate file upload
 * Matches backend ProjectRequests.UploadFileRequest validation
 */
export const validateFileUpload = (file: File): ValidationResult => {
  // Check file extension whitelist
  const validExtensions = ['.owl', '.rdf', '.ttl', '.n3', '.nt', '.jsonld'];
  const extension = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
  
  if (!validExtensions.includes(extension)) {
    return {
      isValid: false,
      error: `Invalid file type. Allowed extensions: ${validExtensions.join(', ')}`
    };
  }

  // Check file size (max 10MB)
  const maxSize = 10 * 1024 * 1024; // 10MB in bytes
  if (file.size > maxSize) {
    return {
      isValid: false,
      error: `File too large. Maximum size: 10MB (current: ${(file.size / 1024 / 1024).toFixed(2)}MB)`
    };
  }

  return { isValid: true };
};

/**
 * Validate subscription plan
 * Matches backend subscription plan enum
 */
export const validateSubscriptionPlan = (plan: string): ValidationResult => {
  const validPlans = ['FREE', 'PRO', 'ENTERPRISE'];
  
  if (!plan) {
    return { isValid: false, error: 'Subscription plan is required' };
  }

  if (!validPlans.includes(plan.toUpperCase())) {
    return { 
      isValid: false, 
      error: `Invalid plan. Allowed values: ${validPlans.join(', ')}` 
    };
  }

  return { isValid: true };
};

/**
 * Check if downgrade would exceed current usage
 * Matches backend WorkspaceController.updateSubscription validation
 */
export const canDowngradePlan = (
  currentPlan: string,
  newPlan: string,
  currentWorkspaceCount: number,
  currentMemberCount: number
): ValidationResult => {
  const planRanks = { FREE: 1, PRO: 2, ENTERPRISE: 3 };
  
  const currentRank = planRanks[currentPlan.toUpperCase() as keyof typeof planRanks] || 1;
  const newRank = planRanks[newPlan.toUpperCase() as keyof typeof planRanks] || 1;

  // Not a downgrade
  if (newRank >= currentRank) {
    return { isValid: true };
  }

  // Check if current usage exceeds new plan limits
  const maxWorkspaces = getMaxWorkspacesForPlan(newPlan);
  const maxMembers = getMaxMembersForPlan(newPlan);

  if (currentWorkspaceCount > maxWorkspaces) {
    return {
      isValid: false,
      error: `Cannot downgrade. You have ${currentWorkspaceCount} workspaces but ${newPlan} plan allows only ${maxWorkspaces}. Please delete ${currentWorkspaceCount - maxWorkspaces} workspace(s) first.`
    };
  }

  if (currentMemberCount > maxMembers) {
    return {
      isValid: false,
      error: `Cannot downgrade. You have ${currentMemberCount} members but ${newPlan} plan allows only ${maxMembers}. Please remove ${currentMemberCount - maxMembers} member(s) first.`
    };
  }

  return { isValid: true };
};

/**
 * Real-time input validator for controlled inputs
 * Returns cleaned value and error message
 */
export const validateInputRealtime = (
  value: string,
  type: 'workspaceName' | 'projectName' | 'description' | 'email'
): { value: string; error: string | null } => {
  let validation: ValidationResult;
  
  switch (type) {
    case 'workspaceName':
      validation = validateWorkspaceName(value);
      break;
    case 'projectName':
      validation = validateProjectName(value);
      break;
    case 'description':
      validation = validateDescription(value);
      break;
    case 'email':
      validation = validateEmail(value);
      break;
    default:
      return { value, error: null };
  }

  return {
    value: validation.sanitized || value,
    error: validation.isValid ? null : (validation.error || 'Invalid input')
  };
};

/**
 * Throttle function calls (for rate limiting simulation)
 */
export const createThrottledFunction = <T extends (...args: any[]) => any>(
  func: T,
  delay: number
): ((...args: Parameters<T>) => void) => {
  let lastCall = 0;
  let timeoutId: NodeJS.Timeout | null = null;

  return (...args: Parameters<T>) => {
    const now = Date.now();
    const timeSinceLastCall = now - lastCall;

    if (timeoutId) {
      clearTimeout(timeoutId);
    }

    if (timeSinceLastCall >= delay) {
      lastCall = now;
      func(...args);
    } else {
      timeoutId = setTimeout(() => {
        lastCall = Date.now();
        func(...args);
      }, delay - timeSinceLastCall);
    }
  };
};

export default {
  sanitizeInput,
  validateLength,
  validateEmail,
  validateWorkspaceName,
  validateProjectName,
  validateDescription,
  validateRole,
  validateFileUpload,
  validateSubscriptionPlan,
  getMaxWorkspacesForPlan,
  getMaxMembersForPlan,
  canDowngradePlan,
  validateInputRealtime,
  createThrottledFunction
};
