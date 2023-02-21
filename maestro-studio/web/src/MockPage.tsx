import { useMemo, useState } from "react"
import { MockEvent } from "./models"
import { formatDistance } from 'date-fns'
import { JsonViewer } from "@textea/json-viewer"
import { CodeSnippet } from "./Examples";
import { API } from "./api";
import MockServerInstructions from "./MockServerInstructions";

const getStatusCodeColor = (statusCode: number): string => {
  if (statusCode < 400) return 'green'
  if (statusCode < 500) return 'orange'
  return 'red'
}

const getMockMethod = (method: string): string => method.toLowerCase()

const LoadingIcon = () => {
  return (
    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" className="w-10 h-10 animate-spin">
      <path d="M23.9997 11.9187C24.0446 18.546 18.7085 23.9548 12.0812 23.9997C5.45398 24.0446 0.0451292 18.7085 0.000234429 12.0813C-0.0446603 5.454 5.29141 0.0451486 11.9187 0.000253852C18.5459 -0.0446409 23.9548 5.29142 23.9997 11.9187ZM3.20567 12.0596C3.23857 16.9165 7.20258 20.8272 12.0595 20.7943C16.9165 20.7614 20.8272 16.7974 20.7942 11.9404C20.7613 7.08345 16.7973 3.17279 11.9404 3.20569C7.08343 3.23859 3.17277 7.2026 3.20567 12.0596Z" fill="#2563EB" fillOpacity="0.4"/>
      <path d="M23.9997 11.9187C24.0168 14.4512 23.2323 16.9242 21.7584 18.9837C20.2846 21.0432 18.1969 22.5836 15.7942 23.3843C13.3916 24.1851 10.7972 24.2052 8.38251 23.4417C5.96776 22.6783 3.85647 21.1704 2.35085 19.134L4.92838 17.2283C6.0318 18.7208 7.57912 19.8258 9.34882 20.3854C11.1185 20.9449 13.0199 20.9301 14.7807 20.3433C16.5415 19.7564 18.0715 18.6275 19.1517 17.1182C20.2319 15.6088 20.8068 13.7964 20.7942 11.9404L23.9997 11.9187Z" fill="#2563EB"/>
    </svg>
  )
}

const safeParse = (res: any, fallback: Object): Object => {
  if (typeof res === 'object') return res

  try {
    const parsed = JSON.parse(res);
    return parsed;
  } catch (err) {
    return fallback
  }
}

const MockPage = () => {
  const [selectedEvent, setSelectedEvent] = useState<MockEvent | undefined>()
  const [query, setQuery] = useState<string>('')
  const {data, isLoading} = API.useMockData({ refreshInterval: 5000 })

  const filteredEvents = useMemo(() => {
    return data?.events?.filter(event => {
      return (`${event.statusCode}`).includes(query) 
        || event.sessionId.includes(query) 
        || event.path.includes(query) 
        || JSON.stringify(event.response 
        || {}).includes(query)
        || (event.matched === true && query === 'matched')
        || (event.matched === false && query === 'unmatched')
    })
  }, [data?.events, query])

  if (isLoading) return (
    <div className="w-full h-full flex justify-center items-center">
      <LoadingIcon />
    </div>
  )

  return (
    <div className="flex flex-col px-8 h-full dark:bg-slate-800 dark:text-white overflow-hidden">
      {(data?.events || []).length === 0 ? (
          <MockServerInstructions projectId={data?.projectId} />
      ) : (
        <>
          <div className="flex flex-row items-center space-x-4 justify-between">
            <div className="flex flex-col gap-4 font-mono p-2 my-4 bg-blue-50 dark:bg-slate-500 dark:border-slate-600 dark:text-white rounded-md border border-blue-400 text-blue-900 basis-1/2">
              <p>Project Id: {data?.projectId || 'unknown'}</p>
            </div>
          </div>
          <div className="flex flex-row pt-2 max-h-full overflow-scroll">
            <div className="flex flex-col w-full basis-2/4 overflow-hidden">
              <h1 className="text-lg font-bold select-none">Events</h1>
              <input
                type="search"
                placeholder="Filter events by session id, path, status code, response"
                className="rounded w-full p-4 pl-2 my-2 bg-slate-100 dark:bg-slate-600 dark:focus:outline-none focus:outline-slate-900"
                value={query}
                onChange={e => setQuery(e.target.value)}
              />
              <div className="h-100 overflow-y-scroll">
                {!data?.events || filteredEvents?.length === 0 ? <p className="text-md">No events found</p> : null}

                {(filteredEvents || []).map(event => (
                  <div
                    className={`flex flex-col my-2 px-4 rounded-md w-full py-2 cursor-pointer text-gray-600 ${selectedEvent !== event ? 'hover:bg-slate-200 dark:hover:bg-slate-700' : ''} active:bg-gray-300 dark:active:bg-slate-850 dark:text-white ${selectedEvent === event ? 'bg-gray-300 dark:bg-slate-850' : 'bg-gray-100 dark:bg-slate-600'}`}
                    onClick={() => setSelectedEvent(event)}
                  >
                    <span className="text-sm text-gray-500 dark:text-slate-400">Session id: {event.sessionId}</span>
                    <div className="flex flex-row justify-between space-x-4 my-1">
                      <span className="grow break-all">{event.method} {event.path}</span>
                      <span className={`pl-4 ${!!event.matched ? 'font-bold' : ''}`}>{!!event.matched ? 'matched' : 'unmatched'}</span>
                      <span className={`text-${getStatusCodeColor(event.statusCode)}-600 font-bold`}>{event.statusCode}</span>
                    </div>
                    <span className={"text-xs text-gray-500 dark:text-slate-400"}>{formatDistance(new Date(event.timestamp), new Date(), { addSuffix: true })}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="flex flex-col basis-2/4 px-8 overflow-hidden">
              <h1 className="text-lg font-bold">{!!selectedEvent?.matched ? 'Mocked response' : 'Response'}</h1>
              {!selectedEvent ? <p className="text-slate-500">No event selected</p> : (
                <>
                  <JsonViewer 
                    value={safeParse(selectedEvent.response, {})}
                    theme="dark"
                    displayDataTypes={false}
                    displayObjectSize={false}
                    defaultInspectDepth={6}
                    rootName={false}
                    style={{padding: 20, fontSize: '1.25em', overflowY: 'scroll', minHeight: '25%', maxHeight: '40%'}}
                  />

                  <h1 className="text-lg font-bold mt-12 mb-2">Here are some examples of how you can mock this network call:</h1>
                  <a
                    className="text-blue-400 underline underline-offset-2 whitespace-nowrap mb-4"
                    href="https://maestro.mobile.dev/advanced/experimental/maestro-mock-server/writing-rules"
                    target="_blank"
                    rel="noopener noreferrer"
                  >View Documentation</a>
                  
                  <div className="h-100 overflow-y-scroll space-y-4 pb-2">
                    <div>
                      <span className="text-slate-500 whitespace-nowrap">Basic</span>
                      <CodeSnippet>
                        {`${getMockMethod(selectedEvent.method)}('${selectedEvent.path}', (req, res, session) => {\n\tres.status(${selectedEvent.statusCode}).json({\n\t\tmockedResponse: true\n\t});\n});`}
                      </CodeSnippet>
                    </div>

                    <div>
                      <span className="text-slate-500 whitespace-nowrap">Use session</span>
                      <CodeSnippet>
                        {`${getMockMethod(selectedEvent.method)}('${selectedEvent.path}', (req, res, session) => {\n\tsession.count = (session.count || 0) + 1;\n\n\tres.status(${selectedEvent.statusCode}).json({\n\t\tmockedResponse: true,\n\t\tcount: session.count\n\t});\n});`}
                      </CodeSnippet>
                    </div>

                    <div>
                      <span className="text-slate-500 whitespace-nowrap">Decorate original response</span>
                      <CodeSnippet>
                        {`${getMockMethod(selectedEvent.method)}('${selectedEvent.path}', async (req, res, session) => {\n\tconst originalRes = await req.propagate();\n\tconst data = originalRes.json();\n\n\tres.status(${selectedEvent.statusCode}).json({\n\t\t...data,\n\t\tmockedResponse: true\n\t});\n});`}
                      </CodeSnippet>
                    </div>
                  </div>
              </>
              )}
            </div>
          </div>
      </>
      )}
    </div>
  )
}

export default MockPage