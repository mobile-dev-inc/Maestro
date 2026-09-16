// BASE_URL and MAESTRO_JS_HTTP_TIMEOUT both arrive as flow env vars, which is the same
// route `--env` and a shell `MAESTRO_*` export take. The timeout is resolved by Orchestra
// from the un-executed DefineVariablesCommand before the JS engine is built.
http.post(BASE_URL + '/slow', {
    body: JSON.stringify({ payload: 'Value' })
})
