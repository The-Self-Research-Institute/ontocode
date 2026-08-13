

export interface ValidationResult {
  isValid: boolean;
  error?: string;
  sanitized?: string;
}

export const sanitizeInput = (input: string): ValidationResult => {
  if (!input) {
    return { isValid: false, error: 'Input is required', sanitized: '' };
  }

  if (/<|>|javascript:|on\w+=/i.test(input)) {
    return { 
      isValid: false, 
      error: 'Invalid characters detected. HTML tags and scripts are not allowed.',
      sanitized: input.replace(/[<>]/g, '')
    };
  }

  if (/(\bSELECT\b|\bINSERT\b|\bUPDATE\b|\bDELETE\b|\bDROP\b|--|\/\*|\*\/)/i.test(input)) {
    return { 
      isValid: false, 
      error: 'Invalid characters detected. SQL commands are not allowed.',
      sanitized: input
    };
  }

  if (/(\.\.[\/\\]|%2e%2e|%252e)/i.test(input)) {
    return { 
      isValid: false, 
      error: 'Invalid path characters detected.',
      sanitized: input
    };
  }

  if (/(%00|\\x00|\\0)/i.test(input)) {
    return { 
      isValid: false, 
      error: 'Invalid null byte detected.',
      sanitized: input
    };
  }

  return { isValid: true, sanitized: input.trim() };
};

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

export const validateEmail = (email: string): ValidationResult => {
  if (!email) {
    return { isValid: false, error: 'Email is required' };
  }

  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  if (!emailRegex.test(email)) {
    return { isValid: false, error: 'Invalid email format' };
  }

  return { isValid: true };
};

export const validateWorkspaceName = (name: string): ValidationResult => {

  if (!name || !name.trim()) {
    return { isValid: false, error: 'Workspace name is required' };
  }

  const sanitized = sanitizeInput(name);
  if (!sanitized.isValid) {
    return sanitized;
  }

  const length = validateLength(name, 1, 255, 'Workspace name');
  if (!length.isValid) {
    return length;
  }

  return { isValid: true, sanitized: name.trim() };
};

export const validateProjectName = (name: string): ValidationResult => {

  if (!name || !name.trim()) {
    return { isValid: false, error: 'Project name is required' };
  }

  const sanitized = sanitizeInput(name);
  if (!sanitized.isValid) {
    return sanitized;
  }

  const length = validateLength(name, 1, 255, 'Project name');
  if (!length.isValid) {
    return length;
  }

  const unsafeChars = /[<>:"/\\|?*]/;
  if (unsafeChars.test(name)) {
    return { 
      isValid: false, 
      error: 'Project name cannot contain: < > : " / \\ | ? *'
    };
  }

  return { isValid: true, sanitized: name.trim() };
};

export const validateDescription = (description: string): ValidationResult => {
  if (!description) {
    return { isValid: true }; // Description is optional
  }

  const sanitized = sanitizeInput(description);
  if (!sanitized.isValid) {
    return sanitized;
  }

  if (description.length > 1000) {
    return { 
      isValid: false, 
      error: 'Description cannot exceed 1000 characters' 
    };
  }

  return { isValid: true };
};

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

export const getMaxMembersForPlan = (plan: string): number => {
  switch (plan.toUpperCase()) {
    case 'FREE':
      return 3;   // Solo + 2 guests — collaboration disabled; must match backend
    case 'PRO':
      return 10;
    case 'ENTERPRISE':
      return Number.MAX_SAFE_INTEGER;
    default:
      return 3;
  }
};

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

export const validateFileUpload = (file: File): ValidationResult => {

  const validExtensions = ['.owl', '.rdf', '.ttl', '.n3', '.nt', '.jsonld'];
  const extension = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();

  if (!validExtensions.includes(extension)) {
    return {
      isValid: false,
      error: `Invalid file type. Allowed extensions: ${validExtensions.join(', ')}`
    };
  }

  const maxSize = 10 * 1024 * 1024; // 10MB in bytes
  if (file.size > maxSize) {
    return {
      isValid: false,
      error: `File too large. Maximum size: 10MB (current: ${(file.size / 1024 / 1024).toFixed(2)}MB)`
    };
  }

  return { isValid: true };
};

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

export const canDowngradePlan = (
  currentPlan: string,
  newPlan: string,
  currentWorkspaceCount: number,
  currentMemberCount: number
): ValidationResult => {
  const planRanks = { FREE: 1, PRO: 2, ENTERPRISE: 3 };

  const currentRank = planRanks[currentPlan.toUpperCase() as keyof typeof planRanks] || 1;
  const newRank = planRanks[newPlan.toUpperCase() as keyof typeof planRanks] || 1;

  if (newRank >= currentRank) {
    return { isValid: true };
  }

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
