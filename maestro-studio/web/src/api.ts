import { DeviceScreen, Repl, ReplCommand } from './models';
import useSWR, { mutate } from 'swr';

export type ReplResponse = {
  repl?: Repl
  error?: any
}

export const wait = async (durationMs: number) => {
  return new Promise(resolve => setTimeout(resolve, durationMs))
}

const makeRequest = async <T>(method: string, path: string, body?: Object | undefined): Promise<T> => {
  const response = await fetch(path, {
    method,
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!response.ok) {
    const body = await response.text()
    throw new Error(`Request failed ${response.status}: ${body}`)
  }
  return await response.json()
}

const useRepl = (): ReplResponse => {
  const {data: repl, error} = useSWR<Repl>('/api/repl/watch')
  return { repl, error }
}

export const API = {
  getDeviceScreen: async (): Promise<DeviceScreen> => {
    return makeRequest('GET', '/api/device-screen')
  },
  repl: {
    useRepl,
    runCommand: async (yaml: string): Promise<Repl> => {
      return makeRequest('POST', '/api/repl/command', { yaml })
    },
    runCommandsById: async (ids: string[]): Promise<Repl> => {
      return makeRequest('POST', '/api/repl/command', { ids })
    },
    deleteCommands: async (ids: string[]): Promise<Repl> => {
      return makeRequest('DELETE', '/api/repl/command', { ids })
    },
    reorderCommands: async (ids: string[]) => {
      makeRequest('POST', '/api/repl/command/reorder', { ids })
      mutate<Repl>('/api/repl/watch', (repl) => {
        if (!repl) return undefined
        const newCommands: ReplCommand[] = []
       const idsAsSet = new Set(ids)
       repl.commands.filter(c => !!c && idsAsSet.has(c.id)).forEach(c => newCommands.push(c))
        return {...repl, commands: newCommands}
      }, {
        revalidate: false
      })
    },
  }
}

const startReplLongPoll = async () => {
  let replVersion = -1
  while (true) {
    try {
      const repl: Repl = await makeRequest('GET', `/api/repl/watch?currentVersion=${replVersion}`)
      mutate('/api/repl/watch', repl, { revalidate: false })
      replVersion = repl.version
    } catch (e) {
      await wait(1000)
    }
  }
}

startReplLongPoll()
