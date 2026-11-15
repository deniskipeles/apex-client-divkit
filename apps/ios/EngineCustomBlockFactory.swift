import UIKit
import SwiftUI
import Charts
import DGCharts
import DivKit

/// Composite DivCustomBlockFactory routing both `json_list` and `sdui_chart`
/// (`bar_chart`, `line_chart`, `area_chart`, `donut_chart`, `pie_chart`) custom DivKit blocks on iOS.
final class EngineCompositeCustomBlockFactory: DivCustomBlockFactory {

    private let jsonListFactory: EngineJsonListBlockFactory
    private let chartFactory: EngineChartBlockFactory

    init(components: DivKitComponents,
         storage: DivVariableStorage,
         actionHandler: DivActionHandler) {
        self.jsonListFactory = EngineJsonListBlockFactory(
            components: components,
            storage: storage,
            actionHandler: actionHandler
        )
        self.chartFactory = EngineChartBlockFactory(
            storage: storage,
            actionHandler: actionHandler
        )
    }

    func makeBlock(data: DivCustomBlockData) -> UIView {
        let blockType = (data.customProps["custom_type"] as? String)
            ?? (data.customProps["chart_type"] as? String)
            ?? data.name
        switch blockType.lowercased() {
        case "sdui_chart", "chart", "bar_chart", "line_chart", "area_chart", "donut_chart", "pie_chart", "bar", "line", "area", "donut", "pie":
            return chartFactory.makeBlock(data: data)
        default:
            return jsonListFactory.makeBlock(data: data)
        }
    }
}

// MARK: - 1. Native iOS Chart Custom Block Factory (Swift Charts + DGCharts)

/// Renders reactive SDUI Charts (`sdui_chart`, `bar_chart`, `line_chart`, `area_chart`, `donut_chart`)
/// using Apple's native **Swift Charts (`Charts.framework`)** on iOS 16+ and **DGCharts** (`BarChartView`,
/// `LineChartView`, `PieChartView`) fallback on iOS 15.
///
/// Consumed by DivKit JSON:
/// ```json
/// {
///   "type": "custom",
///   "custom_type": "sdui_chart",
///   "custom_props": {
///     "chart_type": "bar",
///     "title": "Live POS Revenue by SKU",
///     "data_var": "user.orders_json",
///     "label_field": "item_name",
///     "value_field": "price",
///     "value_prefix": "$"
///   }
/// }
/// ```
final class EngineChartBlockFactory: DivCustomBlockFactory {

    private let storage: DivVariableStorage
    private let actionHandler: DivActionHandler

    init(storage: DivVariableStorage, actionHandler: DivActionHandler) {
        self.storage = storage
        self.actionHandler = actionHandler
    }

    func makeBlock(data: DivCustomBlockData) -> UIView {
        let chartType = (data.customProps["chart_type"] as? String) ?? "bar"
        let title = (data.customProps["title"] as? String) ?? "POS Analytics Chart"
        let subtitle = (data.customProps["subtitle"] as? String) ?? "Swift Charts / DGCharts Native Renderer"
        let dataVar = (data.customProps["data_var"] as? String) ?? "user.orders_json"
        let labelField = (data.customProps["label_field"] as? String) ?? "item_name"
        let valueField = (data.customProps["value_field"] as? String) ?? "price"
        let valuePrefix = (data.customProps["value_prefix"] as? String) ?? ""
        let selectionVar = (data.customProps["selection_var"] as? String) ?? "user.selected_chart_point"
        let inlinePoints = data.customProps["points"] as? [[String: Any]] ?? []

        return NativeChartBlockView(
            storage: storage,
            actionHandler: actionHandler,
            chartType: chartType,
            title: title,
            subtitle: subtitle,
            dataVar: dataVar,
            labelField: labelField,
            valueField: valueField,
            valuePrefix: valuePrefix,
            selectionVar: selectionVar,
            inlinePoints: inlinePoints
        )
    }
}

struct IosChartDataPoint: Identifiable {
    let id: String
    let label: String
    let value: Double
    let colorHex: String
}

private final class NativeChartBlockView: UIView {

    private let storage: DivVariableStorage
    private let actionHandler: DivActionHandler
    private var chartType: String
    private let title: String
    private let subtitle: String
    private let dataVar: String
    private let labelField: String
    private let valueField: String
    private let valuePrefix: String
    private let selectionVar: String
    private let inlinePoints: [[String: Any]]

    private var points: [IosChartDataPoint] = []
    private var cancellable: Any?
    private var hostingView: UIView?
    private let dgBarChartView = DGCharts.BarChartView()
    private let dgLineChartView = DGCharts.LineChartView()
    private let dgPieChartView = DGCharts.PieChartView()

    init(storage: DivVariableStorage,
         actionHandler: DivActionHandler,
         chartType: String,
         title: String,
         subtitle: String,
         dataVar: String,
         labelField: String,
         valueField: String,
         valuePrefix: String,
         selectionVar: String,
         inlinePoints: [[String: Any]]) {
        self.storage = storage
        self.actionHandler = actionHandler
        self.chartType = chartType
        self.title = title
        self.subtitle = subtitle
        self.dataVar = dataVar
        self.labelField = labelField
        self.valueField = valueField
        self.valuePrefix = valuePrefix
        self.selectionVar = selectionVar
        self.inlinePoints = inlinePoints
        super.init(frame: .zero)

        // Subscribe to reactive `user.*` variable updates in DivVariableStorage
        cancellable = storage.publisher(for: dataVar).sink { [weak self] value in
            self?.applyVariableValue(value)
        }
        if let current = storage.getVariable(path: dataVar) {
            applyVariableValue(current)
        } else {
            rebuildFromDictionaries(inlinePoints)
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    private func applyVariableValue(_ value: DivVariableValue) {
        if case .string(let json) = value,
           let data = json.data(using: .utf8),
           let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
           !arr.isEmpty {
            rebuildFromDictionaries(arr)
        } else {
            rebuildFromDictionaries(inlinePoints)
        }
    }

    private func rebuildFromDictionaries(_ dicts: [[String: Any]]) {
        var aggregated: [(String, Double, String)] = []
        let palette = ["#0284C7", "#10B981", "#6366F1", "#F59E0B", "#EC4899", "#8B5CF6"]

        for (idx, item) in dicts.enumerated() {
            let label = (item[labelField] as? String)
                ?? (item["label"] as? String)
                ?? (item["item_name"] as? String)
                ?? (item["name"] as? String)
                ?? "Item \(idx + 1)"
            let qty = (item["qty"] as? Double) ?? Double((item["qty"] as? Int) ?? 1)
            let rawVal = (item[valueField] as? Double)
                ?? Double((item[valueField] as? Int) ?? 0)
                ?? (item["value"] as? Double)
                ?? (item["price"] as? Double)
                ?? 0.0
            let total = (valueField == "price") ? (rawVal * max(qty, 1.0)) : rawVal
            let hex = (item["color_hex"] as? String) ?? palette[idx % palette.count]

            if let existingIndex = aggregated.firstIndex(where: { $0.0 == label }) {
                aggregated[existingIndex].1 += total
            } else {
                aggregated.append((label, total, hex))
            }
        }

        self.points = aggregated.enumerated().map { idx, tuple in
            IosChartDataPoint(id: "pt_\(idx)", label: tuple.0, value: tuple.1, colorHex: tuple.2)
        }
        renderPlatformChart()
    }

    private func renderPlatformChart() {
        hostingView?.removeFromSuperview()
        dgBarChartView.removeFromSuperview()
        dgLineChartView.removeFromSuperview()
        dgPieChartView.removeFromSuperview()

        if #available(iOS 16.0, *) {
            let swiftChartView = SwiftChartsContainerView(
                title: title,
                subtitle: subtitle,
                chartType: chartType,
                valuePrefix: valuePrefix,
                points: points,
                onSelectPoint: { [weak self] selected in
                    guard let self = self else { return }
                    if self.selectionVar.hasPrefix("user.") {
                        self.storage.setVariable(
                            path: .string(self.selectionVar),
                            value: .string("\(selected.label): \(self.valuePrefix)\(String(format: "%.2f", selected.value))")
                        )
                    }
                }
            )
            let controller = UIHostingController(rootView: swiftChartView)
            controller.view.backgroundColor = .clear
            controller.view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(controller.view)
            NSLayoutConstraint.activate([
                controller.view.topAnchor.constraint(equalTo: topAnchor),
                controller.view.leadingAnchor.constraint(equalTo: leadingAnchor),
                controller.view.trailingAnchor.constraint(equalTo: trailingAnchor),
                controller.view.bottomAnchor.constraint(equalTo: bottomAnchor)
            ])
            self.hostingView = controller.view
        } else {
            // DGCharts fallback for iOS 15
            renderDGChartsFallback()
        }
    }

    private func renderDGChartsFallback() {
        switch chartType.lowercased() {
        case "line", "line_chart", "area", "area_chart":
            let entries = points.enumerated().map { idx, pt in
                ChartDataEntry(x: Double(idx), y: pt.value)
            }
            let dataSet = LineChartDataSet(entries: entries, label: title)
            dataSet.mode = .cubicBezier
            dataSet.drawFilledEnabled = true
            dgLineChartView.data = LineChartData(dataSet: dataSet)
            dgLineChartView.translatesAutoresizingMaskIntoConstraints = false
            addSubview(dgLineChartView)
            NSLayoutConstraint.activate([
                dgLineChartView.topAnchor.constraint(equalTo: topAnchor),
                dgLineChartView.leadingAnchor.constraint(equalTo: leadingAnchor),
                dgLineChartView.trailingAnchor.constraint(equalTo: trailingAnchor),
                dgLineChartView.bottomAnchor.constraint(equalTo: bottomAnchor)
            ])
        case "donut", "donut_chart", "pie", "pie_chart":
            let entries = points.map { pt in
                PieChartDataEntry(value: pt.value, label: pt.label)
            }
            let dataSet = PieChartDataSet(entries: entries, label: title)
            dgPieChartView.data = PieChartData(dataSet: dataSet)
            dgPieChartView.drawHoleEnabled = chartType.lowercased().contains("donut")
            dgPieChartView.translatesAutoresizingMaskIntoConstraints = false
            addSubview(dgPieChartView)
            NSLayoutConstraint.activate([
                dgPieChartView.topAnchor.constraint(equalTo: topAnchor),
                dgPieChartView.leadingAnchor.constraint(equalTo: leadingAnchor),
                dgPieChartView.trailingAnchor.constraint(equalTo: trailingAnchor),
                dgPieChartView.bottomAnchor.constraint(equalTo: bottomAnchor)
            ])
        default:
            let entries = points.enumerated().map { idx, pt in
                BarChartDataEntry(x: Double(idx), y: pt.value)
            }
            let dataSet = BarChartDataSet(entries: entries, label: title)
            dgBarChartView.data = BarChartData(dataSet: dataSet)
            dgBarChartView.translatesAutoresizingMaskIntoConstraints = false
            addSubview(dgBarChartView)
            NSLayoutConstraint.activate([
                dgBarChartView.topAnchor.constraint(equalTo: topAnchor),
                dgBarChartView.leadingAnchor.constraint(equalTo: leadingAnchor),
                dgBarChartView.trailingAnchor.constraint(equalTo: trailingAnchor),
                dgBarChartView.bottomAnchor.constraint(equalTo: bottomAnchor)
            ])
        }
    }
}

@available(iOS 16.0, *)
private struct SwiftChartsContainerView: View {
    let title: String
    let subtitle: String
    @State var chartType: String
    let valuePrefix: String
    let points: [IosChartDataPoint]
    let onSelectPoint: (IosChartDataPoint) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    Text(subtitle).font(.caption).foregroundColor(.secondary)
                }
                Spacer()
                Picker("Chart Type", selection: $chartType) {
                    Text("Bar").tag("bar")
                    Text("Line").tag("line")
                    Text("Donut").tag("donut")
                }
                .pickerStyle(.segmented)
                .frame(width: 165)
            }

            Chart(points) { pt in
                if chartType.hasPrefix("line") || chartType.hasPrefix("area") {
                    LineMark(
                        x: .value("Item", pt.label),
                        y: .value("Value", pt.value)
                    )
                    .interpolationMethod(.catmullRom)
                    AreaMark(
                        x: .value("Item", pt.label),
                        y: .value("Value", pt.value)
                    )
                    .interpolationMethod(.catmullRom)
                    .foregroundStyle(.blue.opacity(0.2))
                } else if #available(iOS 17.0, *), (chartType.hasPrefix("donut") || chartType.hasPrefix("pie")) {
                    SectorMark(
                        angle: .value("Value", pt.value),
                        innerRadius: .ratio(0.55),
                        angularInset: 2.0
                    )
                    .foregroundStyle(by: .value("Item", pt.label))
                } else {
                    BarMark(
                        x: .value("Item", pt.label),
                        y: .value("Value", pt.value)
                    )
                    .foregroundStyle(by: .value("Item", pt.label))
                    .cornerRadius(6)
                }
            }
            .frame(height: 190)
        }
        .padding(12)
    }
}

// MARK: - 2. Native JSON List Custom Block Factory

/// Renders a JSON array as a native list.
///
/// Consumed by DivKit JSON:
/// ```json
/// {
///   "type": "json_list",
///   "data_var": "user.messages",
///   "row_card": { ... },
///   "empty_text": "No messages"
/// }
/// ```
///
/// The `data_var` is a `user.*`-namespaced DivKit string variable containing
/// a JSON-encoded array. Each row is its own `DivView` bound to `user.item`.
final class EngineJsonListBlockFactory: DivCustomBlockFactory {

    private let components: DivKitComponents
    private let storage: DivVariableStorage
    private let actionHandler: DivActionHandler

    init(components: DivKitComponents,
         storage: DivVariableStorage,
         actionHandler: DivActionHandler) {
        self.components = components
        self.storage = storage
        self.actionHandler = actionHandler
    }

    func makeBlock(data: DivCustomBlockData) -> UIView {
        let dataVar = (data.customProps["data_var"] as? String) ?? "user.items"
        let rowCard = data.customProps["row_card"] as? [String: Any] ?? [:]
        let emptyText = (data.customProps["empty_text"] as? String) ?? ""

        return JsonListBlockView(
            components: components,
            storage: storage,
            actionHandler: actionHandler,
            dataVar: dataVar,
            rowCard: rowCard,
            emptyText: emptyText
        )
    }
}

// MARK: - Native list

private final class JsonListBlockView: UIView, UITableViewDataSource {

    private let components: DivKitComponents
    private let storage: DivVariableStorage
    private let actionHandler: DivActionHandler
    private let dataVar: String
    private let rowCard: [String: Any]

    private let tableView = UITableView(frame: .zero, style: .plain)
    private let emptyLabel = UILabel()
    private var items: [[String: Any]] = []
    private var cancellable: Any?

    init(components: DivKitComponents,
         storage: DivVariableStorage,
         actionHandler: DivActionHandler,
         dataVar: String,
         rowCard: [String: Any],
         emptyText: String) {
        self.components = components
        self.storage = storage
        self.actionHandler = actionHandler
        self.dataVar = dataVar
        self.rowCard = rowCard
        super.init(frame: .zero)

        emptyLabel.text = emptyText
        emptyLabel.textAlignment = .center
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false

        tableView.dataSource = self
        tableView.register(DivRowCell.self, forCellReuseIdentifier: "cell")
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 80
        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.backgroundColor = .clear

        addSubview(tableView)
        addSubview(emptyLabel)
        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: topAnchor),
            tableView.leadingAnchor.constraint(equalTo: leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: bottomAnchor),
            emptyLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: centerYAnchor)
        ])

        // DivKit iOS exposes a Combine publisher on DivVariableStorage.
        // Subscribe so background sync updates redraw the list.
        cancellable = storage.publisher(for: dataVar).sink { [weak self] value in
            self?.applyValue(value)
        }
        if let current = storage.getVariable(path: dataVar) {
            applyValue(current)
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    private func applyValue(_ value: DivVariableValue) {
        guard case .string(let json) = value,
              let data = json.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            items = []
            tableView.reloadData()
            emptyLabel.isHidden = !items.isEmpty
            tableView.isHidden = items.isEmpty
            return
        }
        items = arr
        tableView.reloadData()
        emptyLabel.isHidden = !items.isEmpty
        tableView.isHidden = items.isEmpty
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        items.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath) as! DivRowCell
        cell.configure(
            components: components,
            actionHandler: actionHandler,
            rowCard: rowCard,
            item: items[indexPath.row]
        )
        return cell
    }
}

// MARK: - Row cell

private final class DivRowCell: UITableViewCell {

    private var host: DivView?

    func configure(components: DivKitComponents,
                   actionHandler: DivActionHandler,
                   rowCard: [String: Any],
                   item: [String: Any]) {
        host?.removeFromSuperview()
        guard let data = try? JSONSerialization.data(withJSONObject: rowCard) else { return }

        let divView = DivView(components: components)
        divView.divActionHandler = actionHandler
        divView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(divView)
        NSLayoutConstraint.activate([
            divView.topAnchor.constraint(equalTo: contentView.topAnchor),
            divView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            divView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            divView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor)
        ])

        // Seed per-row storage
        if let itemData = try? JSONSerialization.data(withJSONObject: item),
           let itemString = String(data: itemData, encoding: .utf8) {
            divView.divVariableStorage.setVariable(
                path: .string("user.item"),
                value: .string(itemString)
            )
        }
        divView.setSource(DivViewSource(kind: .data(data), cardId: "row"))
        host = divView
    }
}
