package com.bgsoftware.wildtools.objects.tools;

import com.bgsoftware.wildtools.api.events.CraftingWandUseEvent;
import com.bgsoftware.wildtools.utils.Executor;
import com.bgsoftware.wildtools.utils.items.ToolTaskManager;
import com.bgsoftware.wildtools.utils.recipes.RecipeChoice;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import com.bgsoftware.wildtools.hooks.WildChestsHook;
import com.bgsoftware.wildtools.Locale;
import com.bgsoftware.wildtools.api.objects.tools.CraftingTool;
import com.bgsoftware.wildtools.api.objects.ToolMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class WCraftingTool extends WTool implements CraftingTool {

    private final List<Recipe> craftings;

    public WCraftingTool(Material type, String name, List<String> craftings){
        super(type, name, ToolMode.CRAFTING);
        this.craftings = parseCraftings(craftings);
    }

    @Override
    public Iterator<Recipe> getCraftings(){
        return craftings.iterator();
    }

    @Override
    public boolean onBlockInteract(PlayerInteractEvent e) {
        if(!plugin.getProviders().canInteract(e.getPlayer(), e.getClickedBlock(), this))
            return false;

        if(e.getClickedBlock().getType() != Material.CHEST && e.getClickedBlock().getType() != Material.TRAPPED_CHEST){
            Locale.INVALID_CONTAINER_CRAFTING.send(e.getPlayer());
            return false;
        }

        UUID taskId = ToolTaskManager.generateTaskId(e.getItem(), e.getPlayer().getInventory());

        Iterator<Recipe> craftings = getCraftings();

        List<ItemStack> toAdd = new ArrayList<>();

        Chest chest = (Chest) e.getClickedBlock().getState();
        Inventory chestInventory = ((InventoryHolder) e.getClickedBlock().getState()).getInventory();

        Executor.async(() -> {
            synchronized (getToolMutex(e.getClickedBlock())) {
                int craftedItemsAmount = 0;

                List<Inventory> inventories = WildChestsHook.getAllInventories(chest, chestInventory);

                while (craftings.hasNext()) {
                    Recipe recipe = craftings.next();
                    List<RecipeChoice> ingredients;

                    //Get the ingredients for the recipe
                    if (recipe instanceof ShapedRecipe) {
                        ingredients = getIngredients(recipe, new ArrayList<>(((ShapedRecipe) recipe).getIngredientMap().values()));
                    } else if (recipe instanceof ShapelessRecipe) {
                        ingredients = getIngredients(recipe, ((ShapelessRecipe) recipe).getIngredientList());
                    } else if (recipe instanceof FurnaceRecipe) {
                        ingredients = Collections.singletonList(RecipeChoice.of(((FurnaceRecipe) recipe).getInput()));
                    } else continue;

                    if (ingredients.isEmpty())
                        continue;

                    for (Inventory inventory : inventories) {
                        int amountOfRecipes = Integer.MAX_VALUE;

                        for (RecipeChoice ingredient : ingredients) {
                            amountOfRecipes = Math.min(amountOfRecipes, countItems(ingredient, inventory) / ingredient.getAmount());
                        }

                        if (amountOfRecipes > 0) {
                            for (RecipeChoice ingredient : ingredients) {
                                ingredient.setAmount(ingredient.getAmount() * amountOfRecipes);
                                ingredient.remove(inventory);
                                if(ingredient.test("BOTTLE"))
                                    toAdd.add(new ItemStack(Material.GLASS_BOTTLE, ingredient.getAmount()));
                                else if(ingredient.test("BUCKET"))
                                    toAdd.add(new ItemStack(Material.BUCKET, ingredient.getAmount()));
                            }

                            ItemStack result = recipe.getResult().clone();
                            result.setAmount(result.getAmount() * amountOfRecipes);
                            toAdd.add(result);

                            craftedItemsAmount += (amountOfRecipes * recipe.getResult().getAmount());
                        }
                    }
                }

                Executor.sync(() -> {
                    CraftingWandUseEvent craftingWandUseEvent = new CraftingWandUseEvent(e.getPlayer(), this,
                            toAdd.stream().map(ItemStack::clone).collect(Collectors.toList()));
                    Bukkit.getPluginManager().callEvent(craftingWandUseEvent);
                });

                WildChestsHook.addItems(chest.getLocation(), chestInventory, toAdd);

                if (craftedItemsAmount > 0) {
                    reduceDurablility(e.getPlayer(), 1, taskId);
                    Locale.CRAFT_SUCCESS.send(e.getPlayer(), craftedItemsAmount);
                } else {
                    Locale.NO_CRAFT_ITEMS.send(e.getPlayer());
                }

                ToolTaskManager.removeTask(taskId);
            }
        });

        return true;
    }

    private List<Recipe> parseCraftings(List<String> recipes){
        List<Recipe> recipeList = new ArrayList<>();

        Recipe current;
        Iterator<Recipe> bukkitRecipes = Bukkit.recipeIterator();

        while (bukkitRecipes.hasNext()){
            current = bukkitRecipes.next();
            if(recipes.contains(current.getResult().getType().name()) ||
                    recipes.contains(current.getResult().getType() + ":" + current.getResult().getDurability()))
                recipeList.add(current);
        }

        return recipeList;
    }

    @SuppressWarnings("deprecation")
    private List<RecipeChoice> getIngredients(Recipe recipe, List<ItemStack> oldList){
        Map<RecipeChoice, Integer> counts = new HashMap<>();
        List<RecipeChoice> ingredients = new ArrayList<>();

        for(ItemStack itemStack : oldList){
            RecipeChoice recipeChoice;

            if(itemStack.getData().getData() < 0){
                recipeChoice = RecipeChoice.of(plugin.getNMSAdapter().parseChoice(recipe, itemStack));
            }else {
                recipeChoice = RecipeChoice.of(itemStack);
            }

            counts.put(recipeChoice, counts.getOrDefault(recipeChoice, 0) + itemStack.getAmount());
        }

        for(RecipeChoice ingredient : counts.keySet()){
            ingredient.setAmount(counts.get(ingredient));
            ingredients.add(ingredient);
        }

        return ingredients;
    }

    private int countItems(RecipeChoice recipeChoice, Inventory inventory){
        int amount = 0;

        for(ItemStack _itemStack : inventory.getContents()){
            if(recipeChoice.test(_itemStack)) {
                amount += _itemStack.getAmount();
            }
        }

        return amount;
    }

}
