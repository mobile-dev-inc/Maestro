import { useConfirmationDialog } from "../components/common/ConfirmationDialog";

export default {
  title: "ConfirmationDialog",
};

export const Main = () => {
  const [show, Dialog] = useConfirmationDialog(() => alert("confirmed!"));

  return (
    <>
      <button
        className="px-4 py-1 border bg-blue-700 text-white rounded cursor-default hover:bg-blue-800 active:bg-blue-900"
        onClick={show}
      >
        Show modal
      </button>
      <Dialog title="This is the title" content="This is the content" />
    </>
  );
};
