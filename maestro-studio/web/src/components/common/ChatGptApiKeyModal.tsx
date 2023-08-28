import { ReactNode, useState } from "react";
import {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogDescription,
  DialogTitle,
} from "../design-system/dialog";
import { Button } from "../design-system/button";
import { Input, InputHint, InputWrapper } from "../design-system/input";
import { API } from "../../api/api";
import _ from "lodash";
import { Spinner } from "../design-system/spinner";
import { useAuth } from "../../context/AuthContext";

export default function ChatGptApiKeyModal({
  open,
  onOpenChange,
  children,
}: {
  open?: boolean;
  onOpenChange?: (val: boolean) => void;
  children?: ReactNode;
}) {
  const { openAiToken, refetchAuth } = useAuth();
  const [token, setToken] = useState<string>(openAiToken || "");
  const [formStates, setFormStates] = useState<{
    isLoading: boolean;
    error: string | ReactNode | null;
  }>({
    isLoading: false,
    error: null,
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormStates({ isLoading: true, error: null });
    try {
      await API.saveOpenAiToken(token);
      refetchAuth();
      setFormStates({ isLoading: false, error: null });
      onOpenChange && onOpenChange(false);
    } catch (error) {
      setFormStates({
        isLoading: false,
        error: _.get(error, "message") || "An unexpected error occurred!",
      });
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent>
        <div className="flex gap-20 p-8 items-stretch">
          <div className="flex-grow min-w-0">
            <DialogHeader className="pb-4">
              <DialogTitle className="text-left text-3xl">
                Personalize Your Experience with ChatGPT API Key
              </DialogTitle>
            </DialogHeader>
            <DialogDescription>
              <p className="text-base mb-6">
                By providing your own ChatGPT API key, you'll benefit from fewer
                interruptions and improved response times.{" "}
                <span className="text-green-600 font-bold">
                  Rest assured, your key remains securely on your device and is
                  never shared or sent to our servers.
                </span>
              </p>
              <form onSubmit={handleSubmit}>
                <InputWrapper error={formStates.error}>
                  <Input
                    value={token}
                    onChange={(e) => setToken(e.target.value)}
                    placeholder="Paste your ChatGPT API key here"
                  />
                  <InputHint />
                </InputWrapper>
                <Button
                  disabled={token === ""}
                  type="submit"
                  size="md"
                  className="mt-4 w-full"
                >
                  {formStates.isLoading && <Spinner size="18" />}
                  {openAiToken ? "Update" : "Activate"} Key
                </Button>
              </form>
            </DialogDescription>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
