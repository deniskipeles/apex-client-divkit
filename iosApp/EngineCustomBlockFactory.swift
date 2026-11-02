import UIKit
import DivKit

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
