import ReplView from "../components/commands/ReplView";
import { useState } from "react";

export default {
  title: "ReplView",
};

export const Main = () => {
  const [input, setInput] = useState("");
  return <ReplView input={input} onInput={setInput} />;
};
