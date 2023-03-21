VERSION_NAME=$(grep VERSION_NAME gradle.properties | cut -d'=' -f2)
sed -i '' "s/{VERSION}/$VERSION_NAME/g" maestro-sdk/rn/package.json
cd maestro-sdk/rn
npm publish