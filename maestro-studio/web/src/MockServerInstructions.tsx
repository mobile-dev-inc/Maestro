import { CodeSnippet } from "./Examples"

const MockServerInstructions = ({ projectId }: { projectId: string }) => (
  <div className="w-full h-full flex justify-center items-center">
    <div className="flex flex-col w-3/4 space-y-6 p-8 shadow-xl border-4 border-blue-300 margin-auto">
      <p className="text-lg font-bold">No events found! Follow the guide below to integrate Maestro SDK.</p>
      
      <div>
        <p className="text-md">First, add the Maestro SDK dependency to your app:</p>
        <CodeSnippet>{`implementation 'dev.mobile:maestro-sdk-android:1.21.4-SNAPSHOT'`}</CodeSnippet>
      </div>

      <div>
        <p className="text-md">Then, initialize the Maestro SDK in your app:</p>
        <CodeSnippet>{`MaestroSDK.init('${projectId}')`}</CodeSnippet>
        <p className="text-md">You can always retrieve your project id later by running <span className="italic">maestro mockserver setup</span>.</p>
      </div>

      <div>
        <p className="text-md">Next step is to update your app to use the API base url provided by Maestro SDK:</p>
        <CodeSnippet>{`val baseUrl = MaestroSdk.mockServer().url("<normal_api_url>")`}</CodeSnippet>
        <p className="text-md">You can then use <span className="italic">baseUrl</span> as you would normally do in your app. Requests will be sent to <span className="italic">https://mock.mobile.dev</span> and forwarded to your original API if no mock rules are found.</p>
      </div>

      <div>
        <p className="text-md">Lastly, deploy a set of rules to the Maestro Mock Server. You can either write your own rules or use the following command to scaffold a sample rule to get started:</p>
        <CodeSnippet>{`maestro mockserver scaffold`}</CodeSnippet>
      </div>

      <p className="font-semibold">That's it! Now, build and run your app and you should start seeing events come in!</p>
    </div>
  </div>
)

export default MockServerInstructions