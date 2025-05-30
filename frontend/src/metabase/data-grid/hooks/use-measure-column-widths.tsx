import {
  type ColumnSizingState,
  type Table as ReactTable,
  flexRender,
} from "@tanstack/react-table";
import React, { useCallback, useEffect } from "react";
import type { Root } from "react-dom/client";

import type { ColumnOptions, DataGridTheme } from "metabase/data-grid/types";
import { pickRowsToMeasure } from "metabase/data-grid/utils/measure";
import { renderRoot } from "metabase/lib/react-compat";
import { isNotNull } from "metabase/lib/types";
import { EmotionCacheProvider } from "metabase/styled-components/components/EmotionCacheProvider";
import { ThemeProvider } from "metabase/ui";

import { DEFAULT_FONT_SIZE } from "../constants";

import { DataGridThemeProvider } from "./use-table-theme";

const HEADER_SPACING = 16;
const BODY_SPACING = 2;

const getTruncatedColumnSizing = (
  columnSizingMap: ColumnSizingState,
  truncateWidth: number,
): ColumnSizingState =>
  Object.fromEntries(
    Object.entries(columnSizingMap).map(([key, value]) => [
      key,
      Math.min(value, truncateWidth),
    ]),
  );

export const useMeasureColumnWidths = <TData, TValue>(
  table: ReactTable<TData>,
  columnsOptions: ColumnOptions<TData, TValue>[],
  truncateLongCellWidth: number,
  theme: DataGridTheme | undefined,
  setMeasuredColumnSizing: (columnSizingMap: ColumnSizingState) => void,
  controlledColumnSizingMap?: ColumnSizingState,
  measurementRenderWrapper?: (
    children: React.ReactElement,
  ) => React.ReactElement,
) => {
  const measureColumnWidths = useCallback(
    (
      preserveColumnSizingMap?: ColumnSizingState, // Preserve column widths, for example from saved settings
      truncatePreserved?: boolean, // Allow truncation of preserved column widths
    ) => {
      const measureRoot = document.createElement("div");
      let measureRootTree: Root | undefined = undefined;
      measureRoot.style.position = "absolute";
      measureRoot.style.top = "-9999px";
      measureRoot.style.left = "-9999px";
      measureRoot.style.visibility = "hidden";
      measureRoot.style.pointerEvents = "none";
      measureRoot.style.zIndex = "-999";
      measureRoot.style.fontSize = DEFAULT_FONT_SIZE;
      document.body.appendChild(measureRoot);

      const onMeasureHeaderRender = (div: HTMLDivElement) => {
        if (div === null) {
          return;
        }

        const elementsMeasures = Array.from(
          div.querySelectorAll("[data-measure-id]"),
        )
          .map((element) => {
            const columnId = element.getAttribute("data-measure-id");
            const type = element.getAttribute("data-measure-type");

            if (columnId == null) {
              return null;
            }

            if (preserveColumnSizingMap?.[columnId] != null) {
              return null;
            }

            const width = (element as HTMLElement).offsetWidth;
            return { columnId, width, type };
          })
          .filter(isNotNull);

        const measuredColumnSizingMap =
          elementsMeasures.reduce<ColumnSizingState>(
            (acc, { columnId, width, type }) => {
              if (!acc[columnId]) {
                acc[columnId] = 0;
              }

              if (type === "header") {
                const headerWidth = width + HEADER_SPACING;
                acc[columnId] = Math.max(acc[columnId], headerWidth);
              } else if (type === "body") {
                const bodyWidth = width + BODY_SPACING;
                acc[columnId] = Math.max(acc[columnId], bodyWidth);
              }

              return acc;
            },
            {},
          );

        setMeasuredColumnSizing(measuredColumnSizingMap);
        const columnSizingMap = truncatePreserved
          ? getTruncatedColumnSizing(
              { ...measuredColumnSizingMap, ...preserveColumnSizingMap },
              truncateLongCellWidth,
            )
          : {
              ...getTruncatedColumnSizing(
                measuredColumnSizingMap,
                truncateLongCellWidth,
              ),
              ...preserveColumnSizingMap,
            };

        table.setColumnSizing(columnSizingMap);

        // Asynchronously unmount the root after the current render has completed to avoid the race condition and an error thrown by React.
        setTimeout(() => {
          measureRootTree?.unmount();
          document.body.removeChild(measureRoot);
        }, 0);
      };

      const rows = table.getRowModel().rows;
      const rowsData = rows.map((row) => row.original);

      const measureContent = (
        <div style={{ display: "flex" }} ref={onMeasureHeaderRender}>
          {table
            .getHeaderGroups()
            .flatMap((headerGroup) => headerGroup.headers)
            .map((header) => {
              const headerCell = flexRender(
                header.column.columnDef.header,
                header.getContext(),
              );
              return (
                <div
                  key={header.column.id}
                  data-measure-id={header.column.id}
                  data-measure-type="header"
                >
                  {headerCell}
                </div>
              );
            })}

          {columnsOptions.map((columnOptions) => {
            return (
              <div
                key={columnOptions.id}
                data-measure-id={columnOptions.id}
                data-measure-type="body"
              >
                {pickRowsToMeasure(rowsData, columnOptions.accessorFn).map(
                  (rowIndex) => {
                    const cell = rows[rowIndex]
                      .getVisibleCells()
                      .find((cell) => cell.column.id === columnOptions.id);

                    if (!cell) {
                      return null;
                    }

                    return (
                      <React.Fragment key={`${columnOptions.id}-${rowIndex}`}>
                        {flexRender(
                          cell.column.columnDef.cell,
                          cell.getContext(),
                        )}
                      </React.Fragment>
                    );
                  },
                )}
              </div>
            );
          })}
        </div>
      );

      const wrappedContent = (
        <EmotionCacheProvider>
          <ThemeProvider>
            <DataGridThemeProvider theme={theme}>
              {measureContent}
            </DataGridThemeProvider>
          </ThemeProvider>
        </EmotionCacheProvider>
      );

      const content = measurementRenderWrapper
        ? measurementRenderWrapper(wrappedContent)
        : wrappedContent;

      measureRootTree = renderRoot(content, measureRoot);
    },
    [
      table,
      columnsOptions,
      theme,
      measurementRenderWrapper,
      setMeasuredColumnSizing,
      truncateLongCellWidth,
    ],
  );

  useEffect(() => {
    measureColumnWidths(controlledColumnSizingMap, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return measureColumnWidths;
};
