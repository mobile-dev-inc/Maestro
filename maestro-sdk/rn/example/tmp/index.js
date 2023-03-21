get('/', (req, res, session) => {
	res.status(200).json({
		mockedResponse: true,
  		message: "Hello World"
	});
});
