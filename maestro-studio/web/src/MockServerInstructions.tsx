import { ReactNode } from "react"
import { CodeSnippet } from "./Examples"

const Link = ({ href, children }: {href: string, children: ReactNode}) => (
  <a
    className="text-blue-400 underline underline-offset-2 whitespace-nowrap mb-4"
    href={href}
    target="_blank"
    rel="noopener noreferrer"
  >{children}</a>
)

const MockServerInstructions = ({ projectId }: { projectId?: string }) => (
  <div className="w-full h-full flex justify-center items-center dark:bg-slate-800 dark:text-white">
    <div className="flex flex-col w-3/4 space-y-6 p-8 shadow-xl border-4 border-blue-300 dark:border-slate-600 margin-auto dark:bg-slate-650">
      <div className="flex">
        <p className="text-lg font-bold grow">No events found! Follow the guide below to integrate Maestro SDK.</p>
      </div>
      
      <div>
        <p className="text-lg">1. Set up Maestro SDK in your app following <Link href="https://maestro.mobile.dev/advanced/experimental/maestro-sdk">the instructions</Link>{!!projectId ? <> using your project id <span className="italic">{projectId}</span></> : null}.</p>
        <p className="text-md">You can retrieve your project id at a later stage by running <span className="italic">maestro mockserver projectid</span>.</p>
      </div>

      <div>
        <p className="text-lg">2. Update your API base url to the one provided by Maestro SDK as outlined <Link href="https://maestro.mobile.dev/advanced/experimental/maestro-mock-server/getting-started">here</Link>.</p>
        <p className="text-md">You can then use <span className="italic">baseUrl</span> as you would normally do in your app. Requests will be sent to <span className="italic">https://mock.mobile.dev</span> and forwarded to your original API if no mock rules are found.</p>
      </div>
      
      <p className="text-lg font-semibold">That's it! Now, build and run your app and you should start seeing events come in!</p>

      <div>
        <p className="text-md mb-2">(Optional) If you want to get started with writing rules right away, you can run the following command to scaffold a sample rule:</p>
        <CodeSnippet>{`maestro mockserver init`}</CodeSnippet>
      </div>

    </div>
  </div>
)

export default MockServerInstructions