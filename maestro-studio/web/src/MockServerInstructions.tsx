import { CodeSnippet } from "./Examples"
import { ThemeToggle } from "./theme"

const MockServerInstructions = ({ projectId }: { projectId?: string }) => (
  <div className="w-full h-full flex justify-center items-center dark:bg-slate-800 dark:text-white">
    <div className="flex flex-col w-3/4 space-y-6 p-8 shadow-xl border-4 border-blue-300 dark:border-slate-600 margin-auto dark:bg-slate-650">
      <div className="flex">
        <p className="text-lg font-bold grow">No events found! Follow the guide below to integrate Maestro SDK.</p>
        <ThemeToggle />
      </div>
      
      <div>
        <p className="text-md">First, add the Maestro SDK dependency to your app:</p>
        <CodeSnippet>{`implementation 'dev.mobile:maestro-sdk-android:+'`}</CodeSnippet>
      </div>

      <div>
        <p className="text-md">Then, initialize the Maestro SDK in your app:</p>
        <CodeSnippet>{`MaestroSdk.init("${projectId || '<your_project_id>'}")`}</CodeSnippet>
        <p className="text-md">You can retrieve your project id by running <span className="italic">maestro mockserver projectid</span>.</p>
      </div>

      <div>
        <p className="text-md">Next step is to update your app to use the API base url provided by Maestro SDK:</p>
        <CodeSnippet>{`val baseUrl = MaestroSdk.mockServer().url("https://api.yourdomain.com")`}</CodeSnippet>
        <p className="text-md">You can then use <span className="italic">baseUrl</span> as you would normally do in your app. Requests will be sent to <span className="italic">https://mock.mobile.dev</span> and forwarded to your original API if no mock rules are found.</p>
      </div>
      
      <p className="font-semibold">That's it! Now, build and run your app and you should start seeing events come in!</p>

      <div>
        <p className="text-md mb-2">(Optional) If you want to get started with writing rules right away, you can run the following command to scaffold a sample rule:</p>
        <CodeSnippet>{`maestro mockserver init`}</CodeSnippet>
      </div>

    </div>
  </div>
)

export default MockServerInstructions