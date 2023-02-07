import MockServerInstructions from './MockServerInstructions';

export default {
  title: 'MockInstructions',
  parameters: {
    layout: 'fullscreen'
  }
}

export const Main = () => {
  return (
    <MockServerInstructions projectId='some-project-id' />
  )
}
