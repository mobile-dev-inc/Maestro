import { useEffect, useRef, useState } from "react";
import _ from "lodash";
import { useAuth } from "../../context/AuthContext";
import { useDeviceContext } from "../../context/DeviceContext";
import AuthModal from "../common/AuthModal";
import { Button } from "../design-system/button";
import { Input, InputHint, InputWrapper } from "../design-system/input";
import { AiSparkles, EnterKey } from "../design-system/utils/images";
import CommandInput from "./CommandInput";
import { API } from "../../api/api";
import { Spinner } from "../design-system/spinner";

type CommandCreatorProps = {
  onSubmit: () => void;
  error: string | null;
  setError: (val: string | null) => void;
};

/************************************************
 * Main Component
 ************************************************/
export default function CommandCreator({
  onSubmit,
  error,
  setError,
}: CommandCreatorProps) {
  const { isAuthenticated } = useAuth();
  const { currentCommandValue, setCurrentCommandValue } = useDeviceContext();

  const [showAuthModal, setShowAuthModal] = useState<boolean>(false);
  const showAiInput = currentCommandValue[0] === " ";

  useEffect(() => {
    const enableAI = currentCommandValue[0] === " ";
    if (enableAI && !isAuthenticated) {
      setShowAuthModal(true);
    }
  }, [isAuthenticated, currentCommandValue]);

  const handleSetValue = (value: string) => {
    setError(null);
    setCurrentCommandValue(value);
  };

  const isHiddenFeatureActive = localStorage.getItem("hidden_feature_active");

  if (!isHiddenFeatureActive) {
    return (
      <CommandForm
        onSubmit={onSubmit}
        error={error}
        setValue={handleSetValue}
      />
    );
  }

  return (
    <div>
      <AuthModal
        open={showAuthModal}
        onOpenChange={(val: boolean) => {
          setShowAuthModal(val);
          setCurrentCommandValue("");
        }}
      />
      {currentCommandValue.length > 0 ? (
        <>
          {showAiInput ? (
            <AiInput />
          ) : (
            <CommandForm
              onSubmit={onSubmit}
              error={error}
              setValue={handleSetValue}
            />
          )}
        </>
      ) : (
        <DefaultInput />
      )}
    </div>
  );
}

/************************************************
 * Default Placed Input
 ************************************************/
const DefaultInput = () => {
  const inputRef = useRef<HTMLInputElement>(null);
  const { currentCommandValue, setCurrentCommandValue } = useDeviceContext();

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  return (
    <Input
      ref={inputRef}
      placeholder="Press ‘space’ for AI, or type commands…"
      value={currentCommandValue}
      onChange={(e) => setCurrentCommandValue(e.target.value)}
    />
  );
};

/************************************************
 * AI Input Form
 ************************************************/
const AiInput = () => {
  const { token } = useAuth();
  const abortControllerRef = useRef<any>(null);
  const { setCurrentCommandValue } = useDeviceContext();
  const aiInputRef = useRef<HTMLInputElement>(null);
  const [userInput, setUserInput] = useState<string>("");
  const [formStates, setFormStates] = useState<{
    isLoading: boolean;
    error: string | null;
  }>({
    isLoading: false,
    error: null,
  });

  useEffect(() => {
    aiInputRef.current?.focus();
  }, []);

  const handleFormSubmit = async (e: React.FormEvent) => {
    abortControllerRef.current = new AbortController();
    e.preventDefault();
    setFormStates({ isLoading: true, error: null });
    try {
      const viewHeir = await API.lastViewHeirarchy();
      const response = await API.generateCommandWithAI(
        viewHeir,
        userInput,
        token,
        abortControllerRef.current.signal
      );
      setFormStates({ isLoading: false, error: null });
      setCurrentCommandValue(`- ${response.command}`);
    } catch (error) {
      let errorMessage;
      if (_.get(error, "name") === "AbortError") {
        errorMessage = "Request was aborted!";
      } else if (_.get(error, "status") === "429") {
        errorMessage = "Exceeded the rate limit";
      } else {
        errorMessage =
          _.get(error, "message") || "An unexpected error occurred!";
      }
      setFormStates({
        isLoading: false,
        error: errorMessage,
      });
    }
  };

  // Function to handle backspace when the input is empty
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Backspace" && userInput === "") {
      setCurrentCommandValue("");
    }
  };

  if (formStates.isLoading) {
    return (
      <div className="flex px-3 h-10 items-center gap-2 relative">
        <div className="absolute top-0 left-0 right-0 bottom-0 rounded-xl bg-gray-100 dark:bg-slate-800/40 animate-pulse z-0" />
        <Spinner size="18" className="relative z-10" />
        <p className="flex-grow text-sm font-semibold relative z-10">
          {userInput}
        </p>
        <Button
          onClick={() => {
            abortControllerRef.current.abort();
            setFormStates({ error: null, isLoading: false });
          }}
          leftIcon="RiStopLine"
          variant="quaternary"
          className="relative z-10"
        >
          Stop Request
        </Button>
      </div>
    );
  }

  return (
    <form className="relative" onSubmit={handleFormSubmit}>
      <InputWrapper error={formStates.error}>
        <Input
          ref={aiInputRef}
          value={userInput}
          onKeyDown={handleKeyDown}
          onChange={(e) => {
            setUserInput(e.target.value);
          }}
          leftElement={<AiSparkles className="w-[18px] text-orange-500" />}
          placeholder="Ask AI to generate command"
        />
        <InputHint />
      </InputWrapper>
      <Button
        disabled={userInput === ""}
        type="submit"
        size="sm"
        className="absolute top-1.5 right-2 p-0 w-[28px] h-[28px]"
      >
        <EnterKey className="w-4" />
      </Button>
    </form>
  );
};

/************************************************
 * Command Input Form
 ************************************************/
const CommandForm = ({
  onSubmit,
  error,
  setValue,
}: {
  onSubmit: () => void;
  error: string | null;
  setValue: (val: string) => void;
}) => {
  const commandInputRef = useRef<HTMLTextAreaElement>(null);
  const { currentCommandValue } = useDeviceContext();

  useEffect(() => {
    const input = commandInputRef.current;
    if (input) {
      input.focus();
      const len = input.value.length;
      input.selectionStart = len;
      input.selectionEnd = len;
    }
  }, []);

  const handleFormSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit();
  };

  return (
    <form className="gap-2 flex flex-col relative" onSubmit={handleFormSubmit}>
      <CommandInput
        ref={commandInputRef}
        setValue={setValue}
        value={currentCommandValue}
        error={error}
        placeholder="Enter a command"
        onSubmit={onSubmit}
      />
      <Button
        disabled={!currentCommandValue || !!error}
        type="submit"
        leftIcon="RiCommandLine"
        size="sm"
        className="absolute bottom-2 right-2 text-lg font-medium"
      >
        +
        <EnterKey className="w-4" />
      </Button>
    </form>
  );
};
