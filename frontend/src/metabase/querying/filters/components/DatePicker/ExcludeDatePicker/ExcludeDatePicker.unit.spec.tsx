import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";

import { renderWithProviders, screen } from "__support__/ui";
import {
  DATE_PICKER_EXTRACTION_UNITS,
  DATE_PICKER_OPERATORS,
} from "metabase/querying/filters/constants";
import type {
  DatePickerExtractionUnit,
  DatePickerOperator,
  ExcludeDatePickerValue,
} from "metabase/querying/filters/types";

import type { DatePickerSubmitButtonProps } from "../types";

import { ExcludeDatePicker } from "./ExcludeDatePicker";

interface SetupOpts {
  availableOperators?: DatePickerOperator[];
  availableUnits?: DatePickerExtractionUnit[];
  renderSubmitButton?: (props: DatePickerSubmitButtonProps) => ReactNode;
}

function setup({
  availableOperators = DATE_PICKER_OPERATORS,
  availableUnits = DATE_PICKER_EXTRACTION_UNITS,
  renderSubmitButton,
}: SetupOpts = {}) {
  const onChange = jest.fn();
  const onBack = jest.fn();

  renderWithProviders(
    <ExcludeDatePicker
      availableOperators={availableOperators}
      availableUnits={availableUnits}
      renderSubmitButton={renderSubmitButton}
      onChange={onChange}
      onBack={onBack}
    />,
  );

  return { onChange, onBack };
}

describe("ExcludeDatePicker", () => {
  it("should allow to exclude days", async () => {
    const { onChange } = setup();

    await userEvent.click(screen.getByText("Days of the week…"));
    await userEvent.click(screen.getByLabelText("Monday"));
    await userEvent.click(screen.getByLabelText("Sunday"));
    expect(screen.getByLabelText("Monday")).toBeChecked();
    expect(screen.getByLabelText("Sunday")).toBeChecked();
    expect(screen.getByLabelText("Tuesday")).not.toBeChecked();

    await userEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onChange).toHaveBeenCalledWith({
      type: "exclude",
      operator: "!=",
      unit: "day-of-week",
      values: [1, 7],
    });
  });

  it("should allow to exclude all options", async () => {
    const { onChange } = setup();

    await userEvent.click(screen.getByText("Days of the week…"));
    expect(screen.getByLabelText("Select all")).not.toBeChecked();
    expect(screen.getByLabelText("Monday")).not.toBeChecked();
    expect(screen.getByRole("button", { name: "Apply" })).toBeDisabled();

    await userEvent.click(screen.getByLabelText("Select all"));
    expect(screen.getByLabelText("Select all")).toBeChecked();
    expect(screen.getByLabelText("Monday")).toBeChecked();
    expect(screen.getByRole("button", { name: "Apply" })).toBeEnabled();

    await userEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onChange).toHaveBeenCalledWith({
      type: "exclude",
      operator: "!=",
      unit: "day-of-week",
      values: [1, 2, 3, 4, 5, 6, 7],
    });
  });

  it("should allow to deselect all options", async () => {
    const { onChange } = setup();

    await userEvent.click(screen.getByText("Days of the week…"));
    await userEvent.click(screen.getByLabelText("Select all"));
    await userEvent.click(screen.getByLabelText("Select all"));

    expect(screen.getByLabelText("Select all")).not.toBeChecked();
    expect(screen.getByLabelText("Monday")).not.toBeChecked();
    expect(screen.getByRole("button", { name: "Apply" })).toBeDisabled();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("should allow to exclude months", async () => {
    const { onChange } = setup();

    await userEvent.click(screen.getByText("Months of the year…"));
    await userEvent.click(screen.getByLabelText("January"));
    await userEvent.click(screen.getByLabelText("December"));
    await userEvent.click(screen.getByRole("button", { name: "Apply" }));

    expect(onChange).toHaveBeenCalledWith({
      type: "exclude",
      operator: "!=",
      unit: "month-of-year",
      values: [1, 12],
    });
  });

  it("should allow to exclude quarters", async () => {
    const { onChange } = setup();

    await userEvent.click(screen.getByText("Quarters of the year…"));
    await userEvent.click(screen.getByLabelText("1st"));
    await userEvent.click(screen.getByLabelText("4th"));
    await userEvent.click(screen.getByRole("button", { name: "Apply" }));

    expect(onChange).toHaveBeenCalledWith({
      type: "exclude",
      operator: "!=",
      unit: "quarter-of-year",
      values: [1, 4],
    });
  });

  it("should allow to exclude hours", async () => {
    const { onChange } = setup();

    await userEvent.click(screen.getByText("Hours of the day…"));
    await userEvent.click(screen.getByLabelText("12 AM"));
    await userEvent.click(screen.getByLabelText("2 AM"));
    await userEvent.click(screen.getByLabelText("5 PM"));
    await userEvent.click(screen.getByRole("button", { name: "Apply" }));

    expect(onChange).toHaveBeenCalledWith({
      type: "exclude",
      operator: "!=",
      unit: "hour-of-day",
      values: [0, 2, 17],
    });
  });

  it("should allow to exclude empty values", async () => {
    const { onChange } = setup();

    await userEvent.click(screen.getByText("Empty values"));

    expect(onChange).toHaveBeenCalledWith({
      type: "exclude",
      operator: "not-null",
      values: [],
    });
  });

  it("should allow to exclude not empty values", async () => {
    const { onChange } = setup();

    await userEvent.click(screen.getByText("Not empty values"));

    expect(onChange).toHaveBeenCalledWith({
      type: "exclude",
      operator: "is-null",
      values: [],
    });
  });

  it("should pass the value to the submit button callback", async () => {
    const renderSubmitButton = jest.fn().mockReturnValue(null);
    setup({ renderSubmitButton });

    const defaultValue: ExcludeDatePickerValue = {
      type: "exclude",
      operator: "!=",
      unit: "hour-of-day",
      values: [],
    };
    await userEvent.click(screen.getByText("Hours of the day…"));
    expect(renderSubmitButton).toHaveBeenLastCalledWith({
      value: defaultValue,
      isDisabled: true,
    });

    await userEvent.click(screen.getByLabelText("5 PM"));
    expect(renderSubmitButton).toHaveBeenLastCalledWith({
      value: { ...defaultValue, values: [17] },
      isDisabled: false,
    });
  });
});
