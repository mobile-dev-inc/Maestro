const response = http.post('https://eu.httpbin.org/anything', {
    multipartForm: {
        imagefile: {
            filePath: "../media/abc.png",
        },
        name: "logo"
    }
})

console.log(JSON.stringify(response.body, null, 2));
