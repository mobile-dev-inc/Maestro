function username() {
  var date = new Date().getTime().toString();
  var username = `test_user_placeholder`.replace("placeholder", date);
  return username;
}

function email() {
  var date = new Date().getTime().toString();
  var email = `test-user-placeholder@test.com`.replace("placeholder", date);
  return email;
}

function password() {
  var date = new Date().getTime().toString();
  var password = `test-user-password-placeholder`.replace("placeholder", date);
  return password;
}

output.credentials = {
  email: email(),
  password: password(),
  username: username(),
};
