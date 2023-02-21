import { ActionModal } from './ActionModal';
import { DeviceScreen, UIElement } from './models';

export default {
  title: 'ActionModal',
  parameters: {
    layout: 'fullscreen'
  },
}

const deviceScreen: DeviceScreen = {
  screenshot: '',
  width: 1,
  height: 1,
  elements: [],
}

const uiElement: UIElement = {
  id: 'idA',
  bounds: { x: 1, y: 2, width: 3, height: 4 },
  text: 'textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA textA',
  textIndex: 10,
  resourceId: 'resourceidA',
  resourceIdIndex: 20,
}

export const Main = () => {
  return (
    <div className="w-full h-full flex">
      <ActionModal
        deviceScreen={deviceScreen}
        uiElement={uiElement}
        onEdit={() => {}}
        onRun={() => {}}
        onClose={() => {}}
      />
    </div>
  )
}
