import { ResponseComposition, rest, RestContext, setupWorker } from 'msw';
import { DeviceScreen, ReplCommand } from './models';
import { sampleElements } from './sampleElements';
import { wait } from './api';

export const mockDeviceScreen: DeviceScreen = {
  screenshot: '/sample-screenshot.png',
  width: 1080,
  height: 2340,
  elements: sampleElements,
}

let nextId = 0
let version = 0
let commands: ReplCommand[] = []

const replResponse = (res: ResponseComposition, ctx: RestContext) => {
  return res(ctx.status(200), ctx.json({
    version,
    commands
  }))
}

const runCommands = async (ids: string[]) => {
  for (const id of ids) {
    const command = commands.find(c => c.id === id)
    if (command) command.status = 'pending'
  }
  version++
  for (const id of ids) {
    await runCommand(id)
  }
}

const runCommand = async (id: string) => {
  const command = commands.find(c => c.id === id)
  if (!command) return
  await wait(200)
  command.status = 'running'
  version++
  await wait(500)
  command.status = 'success'
  version++
}

const createCommand = (yaml: string) => {
  const command: ReplCommand = {
    id: `${nextId++}`,
    yaml,
    status: 'pending',
  }
  commands.push(command)
  runCommand(command.id)
  version++
}

const handlers = [
  rest.get('/api/repl/watch', async (req, res, ctx) => {
    const currentVersionParam = req.url.searchParams.get('currentVersion')
    if (currentVersionParam === null) return res(ctx.status(400))
    const requestVersion = parseInt(currentVersionParam)
    while (requestVersion >= version) {
      await wait(200)
    }
    return replResponse(res, ctx)
  }),
  rest.post('/api/repl/command', async (req, res, ctx) => {
    const {yaml, ids}: { yaml?: string, ids?: string[] } = await req.json()
    if (yaml) {
      if (yaml.includes('error')) {
        return res(ctx.status(400), ctx.text('Invalid command'))
      }
      createCommand(yaml)
    } else if (ids) {
      runCommands(ids)
    } else {
      return res(ctx.status(400))
    }
    return replResponse(res, ctx)
  }),
  rest.delete('/api/repl/command', async (req, res, ctx) => {
    const {ids}: { ids: string[] } = await req.json()
    commands = commands.filter(c => !ids.includes(c.id))
    version++
    return replResponse(res, ctx)
  }),
  rest.post('/api/repl/command/reorder', async (req, res, ctx) => {
    const {ids}: { ids: string[] } = await req.json()
    const newCommands: ReplCommand[] = []
    ids.forEach(id => {
      const command = commands.find(c => c.id === id)
      command && newCommands.push(command)
    })
    commands.forEach(command => {
      if (!newCommands.includes(command)) {
        newCommands.push(command)
      }
    })
    await wait(30)
    commands = newCommands
    version++
    return replResponse(res, ctx)
  }),
  rest.get('/api/device-screen', (req, res, ctx) => {
    return res(ctx.delay(500), ctx.status(200), ctx.json(mockDeviceScreen))
  }),
  rest.post('/api/repl/command/format', async (req, res, ctx) => {
    const {ids}: { ids: string[] } = await req.json()
    const contentString = commands.filter(c => ids.includes(c.id))
      .map(c => c.yaml.endsWith('\n') ? c.yaml : `${c.yaml}\n`)
      .join('')
    return res(ctx.status(200), ctx.json({
      config: 'appId: com.example.app',
      commands: contentString,
    }))
  }),
  rest.get('/api/mock-server/data', (req, res, ctx) => {
    const projectId = '1803cbd0-7258-4878-a16c-1ef0022d2f4a'
    const sessions = [
      '9c4e5640-eaa8-4c81-94b4-efa96192dfd5',
      '7d191905-7a0e-429c-a0d2-ed5c5744ff83'
    ]

    const events = []
    for (const i of [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]) {
      const sessionId = sessions[i % 2]
      const simulateRuntimeError = i % 4 === 0
      const response = simulateRuntimeError ? {
        runtimeError: 'Something went wrong when evaluating rule'
      } : {
        index: i,
        title: `Post title ${i}`,
        body: 'Lorem ipsum dolor sit amet'
      }

      events.push({
        timestamp: Date.now(),
        path: i === 2 ?  `/posts/${i}/alksudhjioash78902qbnpiasy091g089qg978§1gs0789gq078gw180hgb1s8008sg1078g1s08g1s08s1jkshipasha0ish0aisha908sh80ash0asha0shas90-ha` : `/posts/${i}`,
        matched: i % 3 !== 0,
        response,
        method: (i + 1) % 3 === 0 ? 'POST' : 'GET',
        statusCode: simulateRuntimeError ? 500 : (i % 7 === 0 ? 401 : 200) ,
        sessionId: sessionId,
        projectId: projectId,
        })
    }

    return res(ctx.delay(500), ctx.status(200), ctx.json({
      projectId,
      events: []
    }))
  })
]

export const installMocks = () => {
  setupWorker(...handlers).start()
}
