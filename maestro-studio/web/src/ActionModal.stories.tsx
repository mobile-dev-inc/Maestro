import { ActionModal } from './ActionModal';
import { UIElement } from './models';

export default {
  title: 'ActionModal',
  parameters: {
    layout: 'fullscreen'
  },
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
        deviceWidth={1}
        deviceHeight={1}
        uiElement={uiElement}
        onEdit={() => {}}
        onRun={() => {}}
        onClose={() => {}}
      />
    </div>
  )
}
