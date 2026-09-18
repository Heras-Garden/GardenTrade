# How GardenTrade Works

A Garden shop is attached to a physical inventory container. The shop stores the item template, quantity per purchase, price, owner, and container location.

The sign interface is designed to feel like the older ChestShop setup. A wall sign attached to the stock container creates the storefront. Buyers interact with the sign to purchase.

A floating item display and text display are rendered above active shops so players can see what is being sold.

Purchases validate stock and inventory space before taking payment. GardenCore orders are used to record the transaction and support rollback if payment or delivery fails.