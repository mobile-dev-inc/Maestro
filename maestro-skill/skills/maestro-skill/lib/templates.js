/**
 * Common Maestro flow templates for rapid automation
 */

/**
 * Template for basic app launch with screenshot
 * @param {string} appId - Bundle ID or package name
 * @param {string} waitForElement - Element to wait for (optional)
 * @param {string} screenshotPath - Screenshot file path (without extension)
 */
function appLaunch(appId, waitForElement = null, screenshotPath = '/tmp/app-launched') {
  const commands = [
    { launchApp: {} },
    { waitForAnimationToEnd: {} }
  ];
  
  if (waitForElement) {
    commands.push({ assertVisible: waitForElement });
  }
  
  commands.push({ takeScreenshot: screenshotPath });
  
  return {
    appId,
    commands
  };
}

/**
 * Template for login flow
 * @param {string} appId - App ID
 * @param {string} username - Username to input
 * @param {string} password - Password to input  
 * @param {string} successElement - Element that confirms successful login
 */
function loginFlow(appId, username, password, successElement) {
  return {
    appId,
    commands: [
      { launchApp: {} },
      { waitForAnimationToEnd: {} },
      { assertVisible: "Login" },
      { tapOn: "Username" },
      { inputText: username },
      { tapOn: "Password" },
      { inputText: password },
      { tapOn: "Login" },
      { assertVisible: successElement },
      { takeScreenshot: '/tmp/login-success' }
    ]
  };
}

/**
 * Template for form filling
 * @param {string} appId - App ID
 * @param {Array} fields - Array of {label, value} objects
 * @param {string} submitButton - Submit button text/id
 * @param {string} confirmation - Confirmation element
 */
function formFill(appId, fields, submitButton, confirmation) {
  const commands = [
    { launchApp: {} },
    { waitForAnimationToEnd: {} }
  ];
  
  // Add field inputs
  fields.forEach(field => {
    commands.push({ tapOn: field.label });
    commands.push({ inputText: field.value });
  });
  
  commands.push({ tapOn: submitButton });
  commands.push({ assertVisible: confirmation });
  commands.push({ takeScreenshot: '/tmp/form-completed' });
  
  return {
    appId,
    commands
  };
}

/**
 * Template for navigation testing
 * @param {string} appId - App ID
 * @param {Array} navigationSteps - Array of navigation actions
 */
function navigationTest(appId, navigationSteps) {
  const commands = [
    { launchApp: {} },
    { waitForAnimationToEnd: {} }
  ];
  
  navigationSteps.forEach((step, index) => {
    if (step.tap) {
      commands.push({ tapOn: step.tap });
    }
    if (step.assert) {
      commands.push({ assertVisible: step.assert });
    }
    if (step.screenshot) {
      commands.push({ takeScreenshot: `/tmp/nav-step-${index + 1}` });
    }
  });
  
  return {
    appId,
    commands
  };
}

/**
 * Template for scroll and find element
 * @param {string} appId - App ID
 * @param {string} targetElement - Element to find by scrolling
 * @param {string} direction - Scroll direction (UP/DOWN/LEFT/RIGHT)
 */
function scrollAndFind(appId, targetElement, direction = 'DOWN') {
  return {
    appId,
    commands: [
      { launchApp: {} },
      { waitForAnimationToEnd: {} },
      { scrollUntilVisible: {
        element: { text: targetElement },
        direction: direction
      }},
      { assertVisible: targetElement },
      { takeScreenshot: `/tmp/scroll-found-${targetElement.replace(/\s+/g, '-').toLowerCase()}` }
    ]
  };
}

/**
 * Template for app reset and fresh start
 * @param {string} appId - App ID
 * @param {string} firstScreenElement - Element that confirms fresh start
 */
function freshStart(appId, firstScreenElement) {
  return {
    appId,
    commands: [
      { launchApp: { clearState: true } },
      { waitForAnimationToEnd: {} },
      { assertVisible: firstScreenElement },
      { takeScreenshot: '/tmp/fresh-start' }
    ]
  };
}

module.exports = {
  appLaunch,
  loginFlow,
  formFill,
  navigationTest,
  scrollAndFind,
  freshStart
};
