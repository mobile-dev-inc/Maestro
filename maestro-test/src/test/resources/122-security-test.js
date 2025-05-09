console.log("Starting security test");

try {
  const myModule = require("./121-myrequire.js");
  console.log(`Test 1 (Regular require): SUCCESS - ${JSON.stringify(myModule)}`);
} catch (e) {
  console.log(`Test 1 (Regular require): FAILED - ${e.message}`);
}

try {
  const badModule = require("../../../etc/passwd");
  console.log("Test 2 (Path traversal): FAILED - Should not allow path traversal");
} catch (e) {
  console.log(`Test 2 (Path traversal): SUCCESS - ${e.message}`);
}

try {
  const badModule = require("/etc/passwd");
  console.log("Test 3 (Absolute path): FAILED - Should not allow absolute paths");
} catch (e) {
  console.log(`Test 3 (Absolute path): SUCCESS - ${e.message}`);
}

try {
  const badModule = require("./subdir/../../../etc/passwd");
  console.log("Test 4 (Normalized traversal): FAILED - Should not allow normalized path traversal");
} catch (e) {
  console.log(`Test 4 (Normalized traversal): SUCCESS - ${e.message}`);
}

console.log("Security test complete"); 