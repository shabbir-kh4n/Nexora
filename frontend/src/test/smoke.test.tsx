import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { isRunId } from "../api/contract";
import { Composer } from "../components/Composer";
import { installMatchMedia } from "./matchMedia";

describe("test infrastructure", () => {
  it("runs a TypeScript unit test", () => {
    expect(isRunId("123e4567-e89b-42d3-a456-426614174000")).toBe(true);
    expect(isRunId("123E4567-E89B-42D3-A456-426614174000")).toBe(false);
  });

  it("renders a component, handles user-event and jest-dom matchers", async () => {
    const onSubmit = vi.fn();
    render(<Composer busy={false} onSubmit={onSubmit} onStop={vi.fn()} />);
    const send = screen.getByRole("button", { name: "Send question" });
    expect(send).toBeDisabled();

    await userEvent.setup().type(screen.getByLabelText("Your question"), "hello{Enter}");

    expect(onSubmit).toHaveBeenCalledWith("hello");
    expect(screen.getByLabelText("Your question")).toHaveValue("");
  });

  it("provides a matchMedia helper that notifies on change", () => {
    const media = installMatchMedia(() => false);
    const query = window.matchMedia("(min-width: 900px)");
    const listener = vi.fn();
    query.addEventListener("change", listener);

    media.update(() => true);

    expect(query.matches).toBe(true);
    expect(listener).toHaveBeenCalledTimes(1);
  });
});
