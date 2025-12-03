// Fetches test user from API
function getTestUserFromApi() {
  const url = `https://jsonplaceholder.typicode.com/users/1`;
  var response = http.get(url);
  var data = json(response.body);

  return {
    username: data.username,
    email: data.email,
  };
}

output.test_user = getTestUserFromApi();
