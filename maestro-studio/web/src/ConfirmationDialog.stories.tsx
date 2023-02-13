import { useConfirmationDialog } from './ConfirmationDialog';

export default {
  title: 'ConfirmationDialog'
}

export const Main = () => {
  const [show, dialog] = useConfirmationDialog('Confirmation', 'Click confirm to delete the 7 selected commands.', () => alert('confirmed!'))

  return (
    <>
      <button
        className="px-4 py-1 border bg-blue-700 text-white rounded cursor-default hover:bg-blue-800 active:bg-blue-900"
        onClick={show}
      >
        Show modal
      </button>
      {dialog}
    </>
  )
}
